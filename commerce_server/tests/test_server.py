import json
import tempfile
import time
import unittest
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from commerce_server.afdian import AfdianClient, AfdianOAuthClient, QUERY_ORDER_URL
from commerce_server.afdian_store import AfdianMembershipStore
from commerce_server.core import verify_entitlement
from commerce_server.server import create_app


class MembershipApiTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.store = AfdianMembershipStore(Path(self.temp.name) / 'membership.db', self.key)
        self.device = 'device-' + 'x' * 32
        self.plan_active = True

        def post_form(url, body):
            self.assertIn('client_secret=server-secret', body.decode())
            return b'{"ec":200,"data":{"user_id":"supporter-1"}}'

        def post_json(url, body):
            params = json.loads(json.loads(body)['params'])
            if url == QUERY_ORDER_URL:
                return json.dumps({'ec': 200, 'data': {'list': [{
                    'out_trade_no': params['out_trade_no'],
                    'user_id': 'supporter-1' if params['out_trade_no'] != 'stolen' else 'victim',
                    'plan_id': 'pro-plan', 'status': 2, 'product_type': 0,
                    'total_amount': '49.00', 'month': 1,
                }]}}).encode()
            return json.dumps({'ec': 200, 'data': {'total_page': 1, 'list': [{
                'user': {'user_id': 'supporter-1'},
                'current_plan': {'plan_id': 'pro-plan' if self.plan_active else 'other-plan',
                                 'expire_time': int(time.time()) + 86400},
            }]}}).encode()

        oauth = AfdianOAuthClient('app-id', 'server-secret',
                                   'https://example.com/v1/auth/afdian/callback', post_form)
        afdian = AfdianClient('creator', 'api-secret', post_json)
        self.http = TestClient(create_app(self.store, oauth, afdian,
                                          plan_id='pro-plan', amount_minor=4900))

    def tearDown(self):
        self.store.db.close()
        self.temp.cleanup()

    def login(self):
        start = self.http.post('/v1/auth/start', json={'device_id': self.device}).json()
        state = parse_qs(urlsplit(start['authorization_url']).query)['state'][0]
        self.assertEqual(self.http.post('/v1/auth/complete',
                                        json={'login_ticket': start['login_ticket']}).status_code,
                         202)
        self.assertEqual(self.http.get('/v1/auth/afdian/callback',
                                       params={'state': state, 'code': 'valid-code'}).status_code,
                         200)
        complete = self.http.post('/v1/auth/complete',
                                  json={'login_ticket': start['login_ticket']})
        self.assertEqual(complete.status_code, 200)
        self.assertEqual(self.http.post('/v1/auth/complete',
                                        json={'login_ticket': start['login_ticket']}).status_code,
                         202)
        return {'Authorization': 'Bearer ' + complete.json()['session_token']}

    def test_login_order_lookup_and_signed_membership(self):
        self.assertEqual(self.http.get('/v1/membership').status_code, 401)
        auth = self.login()
        self.assertEqual(self.http.get('/v1/membership', headers=auth).json()['status'], 'inactive')
        self.assertEqual(self.http.post('/v1/membership/refresh', headers=auth,
                                        json={'order_no': 'stolen'}).status_code, 422)
        self.assertEqual(self.http.get('/v1/membership', headers=auth).json()['status'], 'inactive')
        refreshed = self.http.post('/v1/membership/refresh', headers=auth,
                                   json={'order_no': 'paid-order'})
        self.assertEqual(refreshed.status_code, 200)
        self.assertEqual(refreshed.json()['status'], 'active')
        proof = refreshed.json()['entitlement']
        self.assertIsNotNone(verify_entitlement(proof, self.device, self.key.public_key()))
        self.assertEqual(self.http.get('/v1/membership', headers=auth).json()['status'], 'active')
        self.plan_active = False
        self.assertEqual(self.http.post('/v1/membership/refresh', headers=auth,
                                        json={}).json()['status'], 'inactive')
        self.assertEqual(self.http.post('/v1/auth/logout', headers=auth).status_code, 200)
        self.assertEqual(self.http.get('/v1/membership', headers=auth).status_code, 401)

    def test_same_afdian_account_can_restore_on_another_device(self):
        original = self.login()
        self.http.post('/v1/membership/refresh', headers=original,
                       json={'order_no': 'paid-order'})
        self.device = 'device-' + 'y' * 32
        restored = self.login()
        result = self.http.post('/v1/membership/refresh', headers=restored, json={}).json()
        self.assertEqual(result['status'], 'active')
        self.assertIsNotNone(verify_entitlement(result['entitlement'], self.device,
                                                self.key.public_key()))

    def test_webhook_uses_provider_query_and_oauth_identity(self):
        callback = {'ec': 200, 'data': {'type': 'order', 'order': {
            'out_trade_no': 'paid-order', 'status': 2, 'user_id': 'forged-user'}}}
        self.assertEqual(self.http.post('/v1/webhooks/afdian', json=callback).json()['ec'], 200)
        self.assertEqual(self.http.post('/v1/webhooks/afdian', json=callback).json()['ec'], 200)
        auth = self.login()
        result = self.http.post('/v1/membership/refresh', headers=auth, json={}).json()
        self.assertEqual(result['status'], 'active')

    def test_webhook_cannot_give_another_users_order_to_this_login(self):
        callback = {'ec': 200, 'data': {'type': 'order', 'order': {
            'out_trade_no': 'stolen', 'status': 2, 'user_id': 'supporter-1'}}}
        self.assertEqual(self.http.post('/v1/webhooks/afdian', json=callback).json()['ec'], 200)
        auth = self.login()
        self.assertEqual(self.http.post('/v1/membership/refresh', headers=auth,
                                        json={}).json()['status'], 'inactive')
        self.assertEqual(self.http.post('/v1/webhooks/afdian',
                                        content=b'x' * 65537).status_code, 413)

    def test_callback_replay_and_unknown_state_rejected(self):
        self.assertEqual(self.http.get('/v1/auth/afdian/callback',
                                       params={'state': 'f' * 43, 'code': 'code'}).status_code, 400)
        start = self.http.post('/v1/auth/start', json={'device_id': self.device}).json()
        state = parse_qs(urlsplit(start['authorization_url']).query)['state'][0]
        params = {'state': state, 'code': 'code'}
        self.assertEqual(self.http.get('/v1/auth/afdian/callback', params=params).status_code, 200)
        self.assertEqual(self.http.get('/v1/auth/afdian/callback', params=params).status_code, 400)


if __name__ == '__main__':
    unittest.main()
