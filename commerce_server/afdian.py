"""Read-only Afdian order adapter. A webhook is a hint, never payment proof.

See https://guide.afdian.com/creator/developer for the signed query-order API.
This module deliberately cannot grant entitlements or call CommerceLedger.settle.
"""
from __future__ import annotations

import hashlib
import json
import re
import secrets
import time
from dataclasses import dataclass
from decimal import Decimal
from typing import Callable
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen


QUERY_ORDER_URL = 'https://afdian.com/api/open/query-order'
QUERY_SPONSOR_URL = 'https://afdian.com/api/open/query-sponsor'
OAUTH_AUTHORIZE_URL = 'https://afdian.com/oauth2/authorize'
OAUTH_TOKEN_URL = 'https://afdian.com/api/oauth2/access_token'
_ORDER_NO = re.compile(r'[A-Za-z0-9_-]{1,128}\Z')
_MONEY = re.compile(r'(?:0|[1-9][0-9]{0,7})\.[0-9]{2}\Z')


class AfdianError(ValueError):
    """The provider result is missing, invalid, or does not match the product."""


@dataclass(frozen=True)
class AfdianMembershipOrder:
    order_no: str
    supporter_user_id: str
    plan_id: str
    amount_minor: int
    months: int


@dataclass(frozen=True)
class AfdianUser:
    user_id: str
    private_id: str | None


def webhook_order_hint(body: bytes) -> str:
    """Return only an order number to query server-side; the body is untrusted."""
    if len(body) > 65536:
        raise AfdianError('webhook body too large')
    try:
        payload = json.loads(body)
        if payload['ec'] != 200 or payload['data']['type'] != 'order':
            raise AfdianError('unsupported webhook event')
        order = payload['data']['order']
        order_no = order['out_trade_no']
        if not isinstance(order_no, str) or not _ORDER_NO.fullmatch(order_no):
            raise AfdianError('invalid order number')
        return order_no
    except (KeyError, TypeError, UnicodeError, json.JSONDecodeError) as exc:
        raise AfdianError('invalid webhook body') from exc


def _post_json(url: str, body: bytes) -> bytes:
    request = Request(url, data=body, headers={'Content-Type': 'application/json'}, method='POST')
    with urlopen(request, timeout=10) as response:
        result = response.read(1048577)
    if len(result) > 1048576:
        raise AfdianError('provider response too large')
    return result


def _post_form(url: str, body: bytes) -> bytes:
    request = Request(url, data=body,
                      headers={'Content-Type': 'application/x-www-form-urlencoded'}, method='POST')
    with urlopen(request, timeout=10) as response:
        result = response.read(1048577)
    if len(result) > 1048576:
        raise AfdianError('provider response too large')
    return result


class AfdianOAuthClient:
    """Server-side OAuth2 helpers; caller must store and consume state once."""

    def __init__(self, client_id: str, client_secret: str, redirect_uri: str,
                 post_form: Callable[[str, bytes], bytes] = _post_form):
        uri = urlsplit(redirect_uri)
        if (not client_id or not client_secret or uri.scheme != 'https' or not uri.hostname or
            uri.username or uri.password or uri.fragment):
            raise ValueError('OAuth2 requires credentials and an HTTPS callback')
        self.client_id = client_id
        self._client_secret = client_secret
        self.redirect_uri = redirect_uri
        self._post_form = post_form

    @staticmethod
    def new_state() -> str:
        return secrets.token_urlsafe(32)

    def authorization_url(self, state: str) -> str:
        if not isinstance(state, str) or len(state) < 32 or len(state) > 128:
            raise ValueError('state must be a persisted high-entropy value')
        return OAUTH_AUTHORIZE_URL + '?' + urlencode({
            'response_type': 'code', 'scope': 'basic', 'client_id': self.client_id,
            'redirect_uri': self.redirect_uri, 'state': state,
        })

    def exchange_code(self, code: str) -> AfdianUser:
        """Call only after the HTTP callback has validated and consumed state."""
        if not isinstance(code, str) or not (1 <= len(code) <= 2048):
            raise ValueError('invalid authorization code')
        body = urlencode({
            'grant_type': 'authorization_code', 'client_id': self.client_id,
            'client_secret': self._client_secret, 'code': code,
            'redirect_uri': self.redirect_uri,
        }).encode('utf-8')
        try:
            response = self._post_form(OAUTH_TOKEN_URL, body)
            if len(response) > 1048576:
                raise AfdianError('provider response too large')
            payload = json.loads(response)
            if payload['ec'] != 200:
                raise AfdianError('provider authorization failed')
            data = payload['data']
            user_id = data['user_id']
            private_id = data.get('user_private_id')
            if (not isinstance(user_id, str) or not 1 <= len(user_id) <= 128 or
                not user_id.isascii() or
                private_id is not None and not isinstance(private_id, str)):
                raise AfdianError('invalid provider identity')
            return AfdianUser(user_id, private_id)
        except (KeyError, TypeError, UnicodeError, json.JSONDecodeError) as exc:
            raise AfdianError('invalid provider identity response') from exc


class AfdianClient:
    def __init__(self, creator_user_id: str, api_token: str,
                 post_json: Callable[[str, bytes], bytes] = _post_json):
        if not creator_user_id or not api_token:
            raise ValueError('creator user ID and API token are required')
        self.creator_user_id = creator_user_id
        self._api_token = api_token
        self._post_json = post_json

    def _query(self, url: str, query_params: dict) -> dict:
        params = json.dumps(query_params, separators=(',', ':'))
        ts = int(time.time())
        signed = f'{self._api_token}params{params}ts{ts}user_id{self.creator_user_id}'
        request = {
            'user_id': self.creator_user_id,
            'params': params,
            'ts': ts,
            'sign': hashlib.md5(signed.encode('utf-8')).hexdigest(),
        }
        body = json.dumps(request, separators=(',', ':')).encode('utf-8')
        try:
            reply_bytes = self._post_json(url, body)
            if len(reply_bytes) > 1048576:
                raise AfdianError('provider response too large')
            reply = json.loads(reply_bytes)
            if reply['ec'] != 200:
                raise AfdianError('provider query failed')
            data = reply['data']
            if not isinstance(data, dict):
                raise AfdianError('invalid provider response')
            return data
        except (KeyError, TypeError, UnicodeError, json.JSONDecodeError) as exc:
            raise AfdianError('invalid provider response') from exc

    def _query_order(self, order_no: str) -> dict:
        if not isinstance(order_no, str) or not _ORDER_NO.fullmatch(order_no):
            raise AfdianError('invalid order number')
        data = self._query(QUERY_ORDER_URL, {'out_trade_no': order_no})
        try:
            orders = data['list']
            if not isinstance(orders, list):
                raise AfdianError('invalid provider order list')
            matches = [order for order in orders if isinstance(order, dict)
                       and order.get('out_trade_no') == order_no]
            if len(matches) != 1:
                raise AfdianError('order not found or ambiguous')
            return matches[0]
        except (KeyError, TypeError) as exc:
            raise AfdianError('invalid provider response') from exc

    def current_plan_expiry(self, supporter_user_id: str, plan_id: str,
                            max_pages: int = 50) -> int | None:
        """Scan the documented sponsor list for one member's current plan.

        A missing/expired plan grants nothing. An incomplete scan raises instead
        of claiming the user is inactive. This is for a small pilot only: the
        public API does not document direct user lookup.
        """
        if not supporter_user_id or not plan_id or not 1 <= max_pages <= 100:
            raise ValueError('invalid sponsor lookup')
        page = 1
        while True:
            data = self._query(QUERY_SPONSOR_URL, {'page': page})
            entries = data.get('list')
            total_pages = data.get('total_page')
            if (not isinstance(entries, list) or type(total_pages) is not int or
                total_pages < 0 or total_pages > max_pages):
                raise AfdianError('invalid or unbounded sponsor list')
            for entry in entries:
                if not isinstance(entry, dict):
                    raise AfdianError('invalid sponsor entry')
                user = entry.get('user')
                if isinstance(user, dict) and user.get('user_id') == supporter_user_id:
                    current = entry.get('current_plan')
                    if not isinstance(current, dict) or current.get('plan_id') != plan_id:
                        return None
                    expiry = current.get('expire_time')
                    if type(expiry) is not int or expiry <= 0:
                        raise AfdianError('invalid plan expiry')
                    return expiry
            if page >= total_pages:
                return None
            page += 1

    def verify_membership_order(self, order_no: str, *, supporter_user_id: str | None = None,
                                plan_id: str, amount_minor: int) -> AfdianMembershipOrder:
        """Verify one paid, fixed-price membership order for an already bound user.

        If supporter_user_id is omitted (trusted webhook flow), the provider's
        user ID is returned but no entitlement is granted until OAuth2 login.
        """
        if not plan_id or amount_minor <= 0:
            raise ValueError('expected plan and price are required')
        order = self._query_order(order_no)
        amount = order.get('total_amount')
        months = order.get('month')
        actual_user = order.get('user_id')
        if (type(order.get('status')) is not int or order['status'] != 2 or
            type(order.get('product_type')) is not int or order['product_type'] != 0 or
            not isinstance(actual_user, str) or not 1 <= len(actual_user) <= 128 or
            not actual_user.isascii() or
            supporter_user_id is not None and actual_user != supporter_user_id or
            order.get('plan_id') != plan_id or
            not isinstance(amount, str) or not _MONEY.fullmatch(amount) or
            int(Decimal(amount) * 100) != amount_minor or
            type(months) is not int or months <= 0):
            raise AfdianError('order does not match membership terms')
        return AfdianMembershipOrder(order_no, actual_user, plan_id,
                                     amount_minor, months)
