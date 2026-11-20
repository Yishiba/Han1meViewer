import json
import unittest
from urllib.parse import parse_qs, urlsplit
from unittest.mock import patch

from commerce_server.afdian import (
    AfdianClient, AfdianError, AfdianOAuthClient, OAUTH_TOKEN_URL,
    QUERY_ORDER_URL, QUERY_SPONSOR_URL, webhook_order_hint,
)


class AfdianAdapterTests(unittest.TestCase):
    def setUp(self):
        self.order = {
            'out_trade_no': 'provider-order-1',
            'user_id': 'bound-supporter',
            'plan_id': 'approved-pro-plan',
            'month': 12,
            'total_amount': '49.00',
            'status': 2,
            'product_type': 0,
        }
        self.seen = []

        def fake_post(url, body):
            self.seen.append((url, json.loads(body)))
            return json.dumps({'ec': 200, 'data': {'list': [self.order]}}).encode()

        self.client = AfdianClient('creator', 'secret', fake_post)

    def verify(self, order_no='provider-order-1'):
        return self.client.verify_membership_order(
            order_no, supporter_user_id='bound-supporter',
            plan_id='approved-pro-plan', amount_minor=4900)

    def test_webhook_only_provides_an_untrusted_lookup_hint(self):
        callback = {'ec': 200, 'data': {'type': 'order', 'order': {
            'out_trade_no': 'provider-order-1', 'status': 2, 'total_amount': '49.00'}}}
        self.assertEqual(webhook_order_hint(json.dumps(callback).encode()), 'provider-order-1')
        self.assertEqual(self.verify().months, 12)
        self.assertEqual(self.seen[0][0], QUERY_ORDER_URL)
        self.assertEqual(json.loads(self.seen[0][1]['params']),
                         {'out_trade_no': 'provider-order-1'})
        self.assertNotIn('secret', json.dumps(self.seen[0][1]))

    def test_official_signing_example(self):
        with patch('commerce_server.afdian.time.time', return_value=1624339905):
            AfdianClient('abc', '123', lambda url, body: (
                self.seen.append((url, json.loads(body))) or
                b'{"ec":200,"data":{"list":[{"out_trade_no":"1"}]}}'
            ))._query_order('1')
        # The documented example uses params {"a":333}; verify the same string rule.
        import hashlib
        self.assertEqual(hashlib.md5(b'123params{"a":333}ts1624339905user_idabc').hexdigest(),
                         'a4acc28b81598b7e5d84ebdc3e91710c')
        self.assertEqual(self.seen[-1][1]['sign'], hashlib.md5(
            b'123params{"out_trade_no":"1"}ts1624339905user_idabc').hexdigest())

    def test_webhook_spoofing_does_not_bypass_provider_lookup(self):
        callback = {'ec': 200, 'data': {'type': 'order', 'order': {
            'out_trade_no': 'provider-order-1', 'user_id': 'bound-supporter',
            'plan_id': 'approved-pro-plan', 'status': 2, 'total_amount': '49.00'}}}
        self.assertEqual(webhook_order_hint(json.dumps(callback).encode()), 'provider-order-1')
        self.order['status'] = 1
        with self.assertRaises(AfdianError):
            self.verify()

    def test_rejects_wrong_user_plan_amount_and_product(self):
        for key, bad in [('user_id', 'someone-else'), ('plan_id', 'different-plan'),
                         ('total_amount', '0.00'), ('product_type', 1), ('month', 0)]:
            with self.subTest(key=key):
                original = self.order[key]
                self.order[key] = bad
                with self.assertRaises(AfdianError):
                    self.verify()
                self.order[key] = original

    def test_missing_or_ambiguous_provider_order_fails_closed(self):
        self.order['out_trade_no'] = 'different-order'
        with self.assertRaises(AfdianError):
            self.verify()
        with self.assertRaises(AfdianError):
            webhook_order_hint(b'{"ec":200,"data":{"type":"order","order":{"out_trade_no":"../x"}}}')

    def test_sponsor_lookup_paginates_and_matches_current_plan(self):
        pages = []

        def fake_post(url, body):
            self.assertEqual(url, QUERY_SPONSOR_URL)
            page = json.loads(json.loads(body)['params'])['page']
            pages.append(page)
            entry = ({'user': {'user_id': 'another-user'}, 'current_plan': {}}
                     if page == 1 else {'user': {'user_id': 'bound-supporter'},
                                        'current_plan': {'plan_id': 'approved-pro-plan',
                                                         'expire_time': 9999}})
            return json.dumps({'ec': 200, 'data': {'total_page': 2, 'list': [entry]}}).encode()

        client = AfdianClient('creator', 'secret', fake_post)
        self.assertEqual(client.current_plan_expiry('bound-supporter', 'approved-pro-plan'), 9999)
        self.assertEqual(pages, [1, 2])
        self.assertIsNone(client.current_plan_expiry('bound-supporter', 'wrong-plan'))

    def test_unbounded_sponsor_scan_fails_closed(self):
        client = AfdianClient('creator', 'secret', lambda url, body:
            b'{"ec":200,"data":{"total_page":1000,"list":[]}}')
        with self.assertRaises(AfdianError):
            client.current_plan_expiry('bound-supporter', 'approved-pro-plan')


class AfdianOAuthTests(unittest.TestCase):
    def setUp(self):
        self.seen = []

        def fake_post(url, body):
            self.seen.append((url, parse_qs(body.decode())))
            return b'{"ec":200,"data":{"user_id":"supporter-1","user_private_id":"private-1"}}'

        self.oauth = AfdianOAuthClient('app-id', 'server-secret',
                                       'https://example.com/oauth/afdian/callback', fake_post)

    def test_authorization_url_and_server_side_exchange(self):
        state = self.oauth.new_state()
        self.assertGreaterEqual(len(state), 32)
        url = urlsplit(self.oauth.authorization_url(state))
        self.assertEqual(url.scheme, 'https')
        self.assertEqual(parse_qs(url.query)['state'], [state])
        self.assertNotIn('server-secret', url.geturl())
        self.assertEqual(self.oauth.exchange_code('one-use-code').user_id, 'supporter-1')
        self.assertEqual(self.seen[0][0], OAUTH_TOKEN_URL)
        self.assertEqual(self.seen[0][1]['client_secret'], ['server-secret'])
        self.assertEqual(self.seen[0][1]['redirect_uri'],
                         ['https://example.com/oauth/afdian/callback'])

    def test_rejects_invalid_config_and_provider_identity(self):
        with self.assertRaises(ValueError):
            AfdianOAuthClient('app-id', 'secret', 'http://example.com/callback')
        with self.assertRaises(ValueError):
            self.oauth.authorization_url('short')
        bad = AfdianOAuthClient('app-id', 'secret', 'https://example.com/callback',
                                lambda url, body: b'{"ec":200,"data":{"user_id":""}}')
        with self.assertRaises(AfdianError):
            bad.exchange_code('code')


if __name__ == '__main__':
    unittest.main()
