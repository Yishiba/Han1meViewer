import sqlite3
import tempfile
import unittest
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric import ec
from commerce_server.core import CommerceLedger, VerifiedPayment, verify_entitlement, YEAR_SECONDS


class LedgerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.ledger = CommerceLedger(Path(self.temp.name) / 'orders.db', self.key, 4900, 'CNY')
        self.device = 'device-' + 'x' * 32
        self.order = self.ledger.create_order(self.device)
        self.payment = VerifiedPayment('verified-provider', 'txn-1', self.order, 4900, 'CNY', 1000)

    def tearDown(self):
        self.ledger.db.close()
        self.temp.cleanup()

    def test_pending_never_grants_access(self):
        self.assertIsNone(self.ledger.entitlement(self.order, self.device, 1001))

    def test_exact_amount_idempotency_and_refund(self):
        wrong = VerifiedPayment('verified-provider', 'txn-1', self.order, 2900, 'CNY', 1000)
        with self.assertRaises(ValueError):
            self.ledger.settle(wrong, 'event-1')
        self.assertTrue(self.ledger.settle(self.payment, 'event-1'))
        self.assertFalse(self.ledger.settle(self.payment, 'event-1'))
        token = self.ledger.entitlement(self.order, self.device, 1001)
        self.assertIsNotNone(token)
        self.assertIsNotNone(verify_entitlement(token, self.device, self.key.public_key(), 1001))
        self.assertIsNone(verify_entitlement(token, 'different-device-12345678', self.key.public_key(), 1001))
        self.assertIsNone(verify_entitlement(token, self.device, self.key.public_key(), 1001 + 86400))
        tampered = token[:-2] + ('A' if token[-2] != 'A' else 'B') + token[-1]
        self.assertIsNone(verify_entitlement(tampered, self.device, self.key.public_key(), 1001))
        self.assertTrue(self.ledger.refund('verified-provider', 'txn-1', 'refund-1'))
        self.assertFalse(self.ledger.refund('verified-provider', 'txn-1', 'refund-1'))
        self.assertIsNone(self.ledger.entitlement(self.order, self.device, 1002))

    def test_transaction_reuse_is_rejected(self):
        self.assertTrue(self.ledger.settle(self.payment, 'event-1'))
        another = self.ledger.create_order('device-' + 'y'*32)
        with self.assertRaises(sqlite3.IntegrityError):
            self.ledger.settle(VerifiedPayment('verified-provider', 'txn-1', another, 4900, 'CNY', 1000), 'event-2')
        self.assertIsNone(self.ledger.entitlement(another, 'device-' + 'y'*32, 1001))

    def test_refund_event_cannot_be_replayed_on_another_transaction(self):
        self.ledger.settle(self.payment, 'paid-1')
        another = self.ledger.create_order('device-' + 'y'*32)
        self.ledger.settle(VerifiedPayment('verified-provider', 'txn-2', another, 4900, 'CNY', 1000), 'paid-2')
        self.assertTrue(self.ledger.refund('verified-provider', 'txn-1', 'refund-1'))
        self.assertFalse(self.ledger.refund('verified-provider', 'txn-2', 'refund-1'))
        self.assertIsNotNone(self.ledger.entitlement(another, 'device-' + 'y'*32, 1001))

    def test_expiration(self):
        self.ledger.settle(self.payment, 'event-1')
        self.assertIsNone(self.ledger.entitlement(self.order, self.device, 1000 + YEAR_SECONDS))

if __name__ == '__main__':
    unittest.main()
