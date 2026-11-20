"""Persistent login tickets, bearer sessions and Afdian membership snapshots.

Only the service layer may call finish_login after OAuth2 code exchange, or
record_current_plan after querying Afdian. No public input can grant access.
"""
from __future__ import annotations

import hashlib
import secrets
import sqlite3
import threading
import time
from dataclasses import dataclass
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric import ec

from commerce_server.afdian import AfdianMembershipOrder
from commerce_server.core import issue_entitlement

LOGIN_TTL = 600
SESSION_TTL = 30 * 86400
STATUS_TTL = 3600


def _digest(value: str) -> str:
    return hashlib.sha256(value.encode('utf-8')).hexdigest()


@dataclass(frozen=True)
class LoginStart:
    state: str
    ticket: str


@dataclass(frozen=True)
class MemberSession:
    supporter_user_id: str
    device_id: str


class AfdianMembershipStore:
    def __init__(self, path: str | Path, signing_key: ec.EllipticCurvePrivateKey):
        if not isinstance(signing_key.curve, ec.SECP256R1):
            raise ValueError('P-256 signing key required')
        self._key = signing_key
        self._lock = threading.RLock()
        self.db = sqlite3.connect(path, isolation_level=None, check_same_thread=False)
        self.db.execute('PRAGMA foreign_keys=ON')
        self.db.executescript('''
            CREATE TABLE IF NOT EXISTS login_requests (
                state_hash TEXT PRIMARY KEY, ticket_hash TEXT NOT NULL UNIQUE,
                device_id TEXT NOT NULL, supporter_id TEXT, expires_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS sessions (
                token_hash TEXT PRIMARY KEY, device_id TEXT NOT NULL,
                supporter_id TEXT NOT NULL, expires_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS afdian_orders (
                order_no TEXT PRIMARY KEY, supporter_id TEXT NOT NULL,
                plan_id TEXT NOT NULL, amount_minor INTEGER NOT NULL,
                months INTEGER NOT NULL, observed_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS current_plans (
                supporter_id TEXT PRIMARY KEY, order_no TEXT,
                paid_until INTEGER, checked_at INTEGER NOT NULL,
                observed_ns INTEGER NOT NULL,
                FOREIGN KEY(order_no) REFERENCES afdian_orders(order_no)
            );
        ''')

    def start_login(self, device_id: str, *, now: int | None = None) -> LoginStart:
        if (not isinstance(device_id, str) or not 16 <= len(device_id) <= 128 or
            not device_id.isascii() or any(c.isspace() for c in device_id)):
            raise ValueError('invalid device identifier')
        now = int(time.time()) if now is None else now
        result = LoginStart(secrets.token_urlsafe(32), secrets.token_urlsafe(32))
        with self._lock:
            self.db.execute('DELETE FROM login_requests WHERE expires_at<=?', (now,))
            self.db.execute('INSERT INTO login_requests VALUES (?,?,?,?,?)',
                            (_digest(result.state), _digest(result.ticket), device_id, None,
                             now + LOGIN_TTL))
        return result

    def finish_login(self, state: str, supporter_user_id: str,
                     *, now: int | None = None) -> bool:
        """Call only after Afdian OAuth2 has returned this authenticated user."""
        if not state or not supporter_user_id:
            raise ValueError('missing OAuth2 identity')
        now = int(time.time()) if now is None else now
        with self._lock:
            updated = self.db.execute('''UPDATE login_requests SET supporter_id=?
                WHERE state_hash=? AND supporter_id IS NULL AND expires_at>?''',
                (supporter_user_id, _digest(state), now))
            return updated.rowcount == 1

    def has_pending_state(self, state: str, *, now: int | None = None) -> bool:
        if not state:
            return False
        now = int(time.time()) if now is None else now
        with self._lock:
            row = self.db.execute('''SELECT 1 FROM login_requests
                WHERE state_hash=? AND supporter_id IS NULL AND expires_at>?''',
                (_digest(state), now)).fetchone()
        return row is not None

    def consume_ticket(self, ticket: str, *, now: int | None = None) -> str | None:
        """Return a new bearer token exactly once after OAuth2 callback succeeds."""
        if not ticket:
            return None
        now = int(time.time()) if now is None else now
        with self._lock:
            self.db.execute('BEGIN IMMEDIATE')
            try:
                row = self.db.execute('''SELECT device_id,supporter_id FROM login_requests
                    WHERE ticket_hash=? AND expires_at>?''', (_digest(ticket), now)).fetchone()
                if row is None or row[1] is None:
                    self.db.execute('ROLLBACK')
                    return None
                token = secrets.token_urlsafe(32)
                self.db.execute('DELETE FROM login_requests WHERE ticket_hash=?', (_digest(ticket),))
                self.db.execute('INSERT INTO sessions VALUES (?,?,?,?)',
                                (_digest(token), row[0], row[1], now + SESSION_TTL))
                self.db.execute('COMMIT')
                return token
            except Exception:
                self.db.execute('ROLLBACK')
                raise

    def session(self, token: str, *, now: int | None = None) -> MemberSession | None:
        if not token:
            return None
        now = int(time.time()) if now is None else now
        with self._lock:
            row = self.db.execute('''SELECT supporter_id,device_id FROM sessions
                WHERE token_hash=? AND expires_at>?''', (_digest(token), now)).fetchone()
        return MemberSession(*row) if row else None

    def logout(self, token: str) -> None:
        if token:
            with self._lock:
                self.db.execute('DELETE FROM sessions WHERE token_hash=?', (_digest(token),))

    def record_order(self, order: AfdianMembershipOrder,
                     session: MemberSession, *, now: int | None = None) -> bool:
        """Save only a provider-verified order belonging to the OAuth2 user."""
        if order.supporter_user_id != session.supporter_user_id:
            raise ValueError('order belongs to another supporter')
        return self.record_provider_order(order, now=now)

    def record_provider_order(self, order: AfdianMembershipOrder,
                              *, now: int | None = None) -> bool:
        """Only a verified provider adapter may call this from the webhook route."""
        now = int(time.time()) if now is None else now
        with self._lock:
            fields = (order.supporter_user_id, order.plan_id, order.amount_minor, order.months)
            inserted = self.db.execute('INSERT OR IGNORE INTO afdian_orders VALUES (?,?,?,?,?,?)',
                                       (order.order_no, *fields, now))
            existing = self.db.execute('''SELECT supporter_id,plan_id,amount_minor,months
                FROM afdian_orders WHERE order_no=?''', (order.order_no,)).fetchone()
            if existing != fields:
                raise ValueError('provider order changed')
            return inserted.rowcount == 1

    def record_current_plan(self, supporter_user_id: str, paid_until: int | None,
                            *, now: int | None = None, observed_ns: int | None = None) -> None:
        """Call with current_plan.expire_time from a fresh provider query."""
        now = int(time.time()) if now is None else now
        observed_ns = time.time_ns() if observed_ns is None else observed_ns
        with self._lock:
            active = paid_until is not None and paid_until > now
            order_no = None
            if active:
                order = self.db.execute('''SELECT order_no FROM afdian_orders
                    WHERE supporter_id=? ORDER BY observed_at DESC,order_no DESC LIMIT 1''',
                    (supporter_user_id,)).fetchone()
                if order is None:
                    raise ValueError('no verified order for supporter')
                order_no = order[0]
            self.db.execute('''INSERT INTO current_plans VALUES (?,?,?,?,?)
                ON CONFLICT(supporter_id) DO UPDATE SET order_no=excluded.order_no,
                paid_until=excluded.paid_until,checked_at=excluded.checked_at,
                observed_ns=excluded.observed_ns
                WHERE excluded.observed_ns>=current_plans.observed_ns''',
                (supporter_user_id, order_no, paid_until if active else None, now, observed_ns))

    def has_verified_order(self, supporter_user_id: str) -> bool:
        with self._lock:
            row = self.db.execute('SELECT 1 FROM afdian_orders WHERE supporter_id=? LIMIT 1',
                                  (supporter_user_id,)).fetchone()
        return row is not None

    def entitlement(self, session: MemberSession,
                    *, now: int | None = None) -> str | None:
        now = int(time.time()) if now is None else now
        with self._lock:
            row = self.db.execute('''SELECT order_no,paid_until,checked_at FROM current_plans
                WHERE supporter_id=?''', (session.supporter_user_id,)).fetchone()
        if row is None or row[0] is None or row[1] is None or row[1] <= now or not 0 <= now - row[2] < STATUS_TTL:
            return None
        return issue_entitlement(self._key, row[0], session.device_id, row[1],
                                 now=now, snapshot_seconds=min(STATUS_TTL, row[2] + STATUS_TTL - now))
