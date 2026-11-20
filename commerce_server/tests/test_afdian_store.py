import tempfile
import unittest
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric import ec

from commerce_server.afdian import AfdianMembershipOrder
from commerce_server.afdian_store import AfdianMembershipStore, STATUS_TTL
from commerce_server.core import verify_entitlement


class AfdianMembershipStoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.store = AfdianMembershipStore(Path(self.temp.name) / 'members.db', self.key)
        self.device = 'device-' + 'x' * 32
        self.login = self.store.start_login(self.device, now=1000)

    def tearDown(self):
        self.store.db.close()
        self.temp.cleanup()

    def test_oauth_state_and_ticket_are_one_time(self):
        self.assertIsNone(self.store.consume_ticket(self.login.ticket, now=1001))
        self.assertFalse(self.store.finish_login('wrong-state', 'user-1', now=1001))
        self.assertTrue(self.store.finish_login(self.login.state, 'user-1', now=1001))
        self.assertFalse(self.store.finish_login(self.login.state, 'user-2', now=1001))
        token = self.store.consume_ticket(self.login.ticket, now=1002)
        self.assertIsNotNone(token)
        self.assertIsNone(self.store.consume_ticket(self.login.ticket, now=1002))
        self.assertEqual(self.store.session(token, now=1002).supporter_user_id, 'user-1')
        self.store.logout(token)
        self.assertIsNone(self.store.session(token, now=1003))

    def test_expired_login_cannot_complete(self):
        self.assertFalse(self.store.finish_login(self.login.state, 'user-1', now=1600))
        self.assertIsNone(self.store.consume_ticket(self.login.ticket, now=1600))

    def test_verified_order_and_fresh_plan_required_for_entitlement(self):
        self.store.finish_login(self.login.state, 'user-1', now=1001)
        token = self.store.consume_ticket(self.login.ticket, now=1002)
        session = self.store.session(token, now=1002)
        self.assertIsNone(self.store.entitlement(session, now=1002))
        order = AfdianMembershipOrder('order-1', 'user-1', 'pro-plan', 4900, 1)
        self.assertTrue(self.store.record_order(order, session, now=1003))
        self.assertFalse(self.store.record_order(order, session, now=1004))
        self.assertIsNone(self.store.entitlement(session, now=1004))
        self.store.record_current_plan('user-1', 5000, now=1005)
        proof = self.store.entitlement(session, now=1006)
        self.assertIsNotNone(verify_entitlement(proof, self.device, self.key.public_key(), 1006))
        self.assertIsNone(verify_entitlement(proof, 'device-' + 'y' * 32,
                                              self.key.public_key(), 1006))
        self.assertIsNone(self.store.entitlement(session, now=1005 + STATUS_TTL))
        self.store.record_current_plan('user-1', None, now=1010)
        self.assertIsNone(self.store.entitlement(session, now=1011))

    def test_other_users_order_cannot_be_claimed(self):
        self.store.finish_login(self.login.state, 'user-1', now=1001)
        token = self.store.consume_ticket(self.login.ticket, now=1002)
        session = self.store.session(token, now=1002)
        with self.assertRaises(ValueError):
            self.store.record_order(AfdianMembershipOrder('order-1', 'user-2',
                                                            'pro-plan', 4900, 1), session, now=1003)
        with self.assertRaises(ValueError):
            self.store.record_current_plan('user-1', 5000, now=1004)

    def test_older_active_lookup_cannot_undo_newer_revocation(self):
        self.store.finish_login(self.login.state, 'user-1', now=1001)
        token = self.store.consume_ticket(self.login.ticket, now=1002)
        session = self.store.session(token, now=1002)
        self.store.record_order(AfdianMembershipOrder('order-1', 'user-1',
                                                       'pro-plan', 4900, 1), session, now=1003)
        self.store.record_current_plan('user-1', 5000, now=1004, observed_ns=100)
        self.store.record_current_plan('user-1', None, now=1005, observed_ns=300)
        self.store.record_current_plan('user-1', 5000, now=1006, observed_ns=200)
        self.assertIsNone(self.store.entitlement(session, now=1007))


if __name__ == '__main__':
    unittest.main()
