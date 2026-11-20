"""Merchant-independent order ledger and signed entitlements.

Only a verified payment adapter may call settle/refund. There is intentionally no
public endpoint that turns an unverified callback into paid access.
"""
from __future__ import annotations

import base64
import json
import secrets
import sqlite3
import time
from dataclasses import dataclass
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec

YEAR_SECONDS = 365 * 86400


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b'=').decode('ascii')


def _unb64(data: str) -> bytes:
    return base64.urlsafe_b64decode(data + '=' * (-len(data) % 4))


@dataclass(frozen=True)
class VerifiedPayment:
    """Constructed only after provider-side transaction verification."""
    provider: str
    transaction_id: str
    order_id: str
    amount_minor: int
    currency: str
    paid_at: int


class CommerceLedger:
    def __init__(self, path: str | Path, signing_key: ec.EllipticCurvePrivateKey, price_minor: int, currency: str):
        if not (1 <= price_minor <= 1_000_000) or currency not in {'USD', 'EUR', 'CNY'}:
            raise ValueError('invalid server price')
        if not isinstance(signing_key.curve, ec.SECP256R1):
            raise ValueError('only P-256 signing keys are supported')
        self.price_minor = price_minor
        self.currency = currency
        self.db = sqlite3.connect(path, isolation_level=None)
        self.db.execute('PRAGMA foreign_keys=ON')
        self.key = signing_key
        self.db.executescript('''
            CREATE TABLE IF NOT EXISTS orders (
                id TEXT PRIMARY KEY, device_id TEXT NOT NULL, amount_minor INTEGER NOT NULL,
                currency TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('pending','paid','refunded')),
                provider TEXT, transaction_id TEXT, paid_at INTEGER, expires_at INTEGER,
                UNIQUE(provider, transaction_id)
            );
            CREATE TABLE IF NOT EXISTS processed_events (
                provider TEXT NOT NULL, event_id TEXT NOT NULL,
                PRIMARY KEY(provider, event_id)
            );
        ''')

    def create_order(self, device_id: str) -> str:
        if not (16 <= len(device_id) <= 128 and device_id.isascii()) or any(c.isspace() for c in device_id):
            raise ValueError('invalid device identifier')
        order_id = secrets.token_urlsafe(24)
        self.db.execute('INSERT INTO orders VALUES (?,?,?,?,?,?,?,?,?)',
                        (order_id, device_id, self.price_minor, self.currency, 'pending', None, None, None, None))
        return order_id

    def settle(self, payment: VerifiedPayment, event_id: str) -> bool:
        """Idempotent. Caller must independently verify merchant, amount and payment with provider."""
        if not payment.provider or not payment.transaction_id or not event_id:
            raise ValueError('missing verified identifiers')
        if payment.paid_at <= 0:
            raise ValueError('invalid payment time')
        self.db.execute('BEGIN IMMEDIATE')
        try:
            row = self.db.execute('SELECT amount_minor,currency,state FROM orders WHERE id=?',
                                  (payment.order_id,)).fetchone()
            if row is None or row[0:2] != (payment.amount_minor, payment.currency):
                raise ValueError('unknown order or amount mismatch')
            if row[2] != 'pending':
                self.db.execute('ROLLBACK')
                return False
            if self.db.execute('SELECT 1 FROM processed_events WHERE provider=? AND event_id=?',
                               (payment.provider, event_id)).fetchone():
                self.db.execute('ROLLBACK')
                return False
            self.db.execute('INSERT INTO processed_events VALUES (?,?)', (payment.provider, event_id))
            self.db.execute('''UPDATE orders SET state='paid', provider=?, transaction_id=?,
                paid_at=?, expires_at=? WHERE id=?''',
                (payment.provider, payment.transaction_id, payment.paid_at,
                 payment.paid_at + YEAR_SECONDS, payment.order_id))
            self.db.execute('COMMIT')
            return True
        except Exception:
            self.db.execute('ROLLBACK')
            raise

    def refund(self, provider: str, transaction_id: str, event_id: str) -> bool:
        """Only a verified provider adapter may call this with a confirmed refund/chargeback."""
        if not provider or not transaction_id or not event_id:
            raise ValueError('missing verified identifiers')
        self.db.execute('BEGIN IMMEDIATE')
        try:
            if self.db.execute('SELECT 1 FROM processed_events WHERE provider=? AND event_id=?',
                               (provider, event_id)).fetchone():
                self.db.execute('ROLLBACK')
                return False
            row = self.db.execute('SELECT id,state FROM orders WHERE provider=? AND transaction_id=?',
                                  (provider, transaction_id)).fetchone()
            if row is None or row[1] != 'paid':
                self.db.execute('ROLLBACK')
                return False
            self.db.execute('INSERT INTO processed_events VALUES (?,?)', (provider, event_id))
            self.db.execute("UPDATE orders SET state='refunded' WHERE id=?", (row[0],))
            self.db.execute('COMMIT')
            return True
        except Exception:
            self.db.execute('ROLLBACK')
            raise

    def entitlement(self, order_id: str, device_id: str, now: int | None = None) -> str | None:
        now = int(time.time()) if now is None else now
        row = self.db.execute('SELECT device_id,state,expires_at FROM orders WHERE id=?', (order_id,)).fetchone()
        if row is None or row[0] != device_id or row[1] != 'paid' or row[2] <= now:
            return None
        return issue_entitlement(self.key, order_id, device_id, row[2], now=now)


def issue_entitlement(key: ec.EllipticCurvePrivateKey, order_id: str, device_id: str,
                      paid_until: int, *, now: int | None = None,
                      snapshot_seconds: int = 86400) -> str:
    """Sign a short-lived snapshot after a trusted ledger has verified access."""
    now = int(time.time()) if now is None else now
    if (not order_id or not device_id or paid_until <= now or
        not 1 <= snapshot_seconds <= 86400 or not isinstance(key.curve, ec.SECP256R1)):
        raise ValueError('invalid entitlement input')
    payload = {'v': 2, 'alg': 'ES256', 'order': order_id, 'device': device_id,
               'issued': now, 'valid_until': min(now + snapshot_seconds, paid_until),
               'paid_until': paid_until}
    encoded = _b64(json.dumps(payload, sort_keys=True, separators=(',', ':')).encode())
    return encoded + '.' + _b64(key.sign(encoded.encode('ascii'), ec.ECDSA(hashes.SHA256())))


def verify_entitlement(token: str, device_id: str, public_key: ec.EllipticCurvePublicKey,
                       now: int | None = None) -> dict | None:
    now = int(time.time()) if now is None else now
    try:
        encoded, signature = token.split('.')
        public_key.verify(_unb64(signature), encoded.encode('ascii'), ec.ECDSA(hashes.SHA256()))
        data = json.loads(_unb64(encoded))
        if (data['v'] != 2 or data['alg'] != 'ES256' or data['device'] != device_id or
            not isinstance(data['order'], str) or not data['order'] or
            any(type(data[field]) is not int for field in ('issued', 'valid_until', 'paid_until')) or
            not 0 <= data['issued'] <= now < data['valid_until'] <= data['paid_until'] or
            data['valid_until'] - data['issued'] > 86400):
            return None
        return data
    except (ValueError, KeyError, TypeError, UnicodeError, json.JSONDecodeError):
        return None
    except Exception:
        return None


def public_key_pem(key: ec.EllipticCurvePrivateKey) -> bytes:
    return key.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
