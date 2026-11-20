"""Afdian membership API prototype. Deploy only after production hardening.

Start with: uvicorn commerce_server.server:create_app_from_env --factory
The OAuth callback is a browser page; the app polls its one-time login ticket.
"""
from __future__ import annotations

import os
import time
from pathlib import Path
from typing import Optional
from urllib.error import URLError
from urllib.parse import urlsplit

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import FastAPI, Header, HTTPException, Query, Request, Response, status
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel, Field

from commerce_server.afdian import AfdianClient, AfdianError, AfdianOAuthClient, webhook_order_hint
from commerce_server.afdian_store import AfdianMembershipStore, MemberSession


class LoginStartRequest(BaseModel):
    device_id: str = Field(min_length=16, max_length=128)


class LoginCompleteRequest(BaseModel):
    login_ticket: str = Field(min_length=32, max_length=128)


class RefreshRequest(BaseModel):
    order_no: Optional[str] = Field(default=None, max_length=128)


def create_app(store: AfdianMembershipStore, oauth: AfdianOAuthClient,
               afdian: AfdianClient, *, plan_id: str, amount_minor: int) -> FastAPI:
    if not plan_id or amount_minor <= 0:
        raise ValueError('approved plan and price are required')
    if urlsplit(oauth.redirect_uri).path != '/v1/auth/afdian/callback':
        raise ValueError('OAuth redirect URI must match callback route')
    app = FastAPI(title='Membership API', docs_url=None, redoc_url=None, openapi_url=None)

    def member(authorization: Optional[str]) -> tuple[str, MemberSession]:
        if not authorization or not authorization.startswith('Bearer '):
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, 'login required')
        token = authorization[7:]
        session = store.session(token)
        if session is None:
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, 'login required')
        return token, session

    def current_status(session: MemberSession) -> dict:
        proof = store.entitlement(session)
        return {'status': 'active' if proof else 'inactive', 'entitlement': proof}

    @app.post('/v1/auth/start')
    def start(request: LoginStartRequest):
        try:
            login = store.start_login(request.device_id)
        except ValueError:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, 'invalid device ID')
        return {'authorization_url': oauth.authorization_url(login.state),
                'login_ticket': login.ticket}

    @app.get('/v1/auth/afdian/callback')
    def callback(state: str = Query(min_length=32, max_length=128),
                 code: str = Query(min_length=1, max_length=2048)):
        if not store.has_pending_state(state):
            raise HTTPException(status.HTTP_400_BAD_REQUEST, 'login request expired')
        try:
            user = oauth.exchange_code(code)
        except (AfdianError, URLError):
            raise HTTPException(status.HTTP_502_BAD_GATEWAY, 'authorization failed')
        if not store.finish_login(state, user.user_id):
            raise HTTPException(status.HTTP_400_BAD_REQUEST, 'login request expired')
        return Response('Authorization complete. Return to the app.',
                        media_type='text/plain', headers={'Cache-Control': 'no-store'})

    @app.post('/v1/auth/complete')
    def complete(request: LoginCompleteRequest, response: Response):
        token = store.consume_ticket(request.login_ticket)
        response.headers['Cache-Control'] = 'no-store'
        if token is None:
            response.status_code = status.HTTP_202_ACCEPTED
            return {'status': 'pending'}
        return {'status': 'authenticated', 'session_token': token}

    @app.post('/v1/auth/logout')
    def logout(authorization: Optional[str] = Header(default=None)):
        token, _ = member(authorization)
        store.logout(token)
        return {'status': 'logged_out'}

    @app.post('/v1/webhooks/afdian')
    async def afdian_webhook(request: Request):
        body = bytearray()
        async for chunk in request.stream():
            if len(body) + len(chunk) > 65536:
                raise HTTPException(status.HTTP_413_CONTENT_TOO_LARGE, 'webhook too large')
            body.extend(chunk)
        try:
            order_no = webhook_order_hint(bytes(body))
            # The unsigned callback is only a hint. Query Afdian before writing
            # any order; the OAuth2 account still controls who can claim it.
            order = await run_in_threadpool(
                afdian.verify_membership_order, order_no,
                plan_id=plan_id, amount_minor=amount_minor)
            store.record_provider_order(order)
        except AfdianError:
            raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE,
                                'provider order could not be verified')
        except URLError:
            raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE,
                                'provider temporarily unavailable')
        return {'ec': 200, 'em': ''}

    @app.get('/v1/membership')
    def membership(authorization: Optional[str] = Header(default=None)):
        _, session = member(authorization)
        return current_status(session)

    @app.post('/v1/membership/refresh')
    def refresh(request: RefreshRequest, authorization: Optional[str] = Header(default=None)):
        _, session = member(authorization)
        try:
            if request.order_no is not None:
                order = afdian.verify_membership_order(
                    request.order_no, supporter_user_id=session.supporter_user_id,
                    plan_id=plan_id, amount_minor=amount_minor)
                store.record_order(order, session)
            if not store.has_verified_order(session.supporter_user_id):
                return current_status(session)
            observed_ns = time.time_ns()
            expiry = afdian.current_plan_expiry(session.supporter_user_id, plan_id)
            store.record_current_plan(session.supporter_user_id, expiry,
                                      observed_ns=observed_ns)
            return current_status(session)
        except AfdianError:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT,
                                'order or membership could not be verified')
        except URLError:
            raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE,
                                'provider temporarily unavailable')

    return app


def create_app_from_env() -> FastAPI:
    """Fail startup if any merchant setting or server private key is absent."""
    required = ('COMMERCE_DB_PATH', 'COMMERCE_SIGNING_KEY_PEM', 'AFDIAN_CREATOR_USER_ID',
                'AFDIAN_API_TOKEN', 'AFDIAN_OAUTH_CLIENT_ID', 'AFDIAN_OAUTH_CLIENT_SECRET',
                'AFDIAN_OAUTH_REDIRECT_URI', 'AFDIAN_PRO_PLAN_ID', 'AFDIAN_PRO_AMOUNT_MINOR')
    missing = [name for name in required if not os.environ.get(name)]
    if missing:
        raise RuntimeError('missing merchant configuration: ' + ', '.join(missing))
    key = serialization.load_pem_private_key(
        Path(os.environ['COMMERCE_SIGNING_KEY_PEM']).read_bytes(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey):
        raise ValueError('EC signing key required')
    return create_app(
        AfdianMembershipStore(os.environ['COMMERCE_DB_PATH'], key),
        AfdianOAuthClient(os.environ['AFDIAN_OAUTH_CLIENT_ID'],
                          os.environ['AFDIAN_OAUTH_CLIENT_SECRET'],
                          os.environ['AFDIAN_OAUTH_REDIRECT_URI']),
        AfdianClient(os.environ['AFDIAN_CREATOR_USER_ID'], os.environ['AFDIAN_API_TOKEN']),
        plan_id=os.environ['AFDIAN_PRO_PLAN_ID'],
        amount_minor=int(os.environ['AFDIAN_PRO_AMOUNT_MINOR']),
    )
