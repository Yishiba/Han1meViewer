"""Grant a Firebase Auth user access to the announcement manager.

Requires a service account JSON kept outside this repository. Prefer the Firebase
MCP auth_update_user tool when available so no service account key is needed.
"""

import argparse

import firebase_admin
from firebase_admin import auth, credentials


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("uid", help="Firebase Auth user UID")
    parser.add_argument("--service-account", required=True, help="Path to service account JSON")
    args = parser.parse_args()

    firebase_admin.initialize_app(credentials.Certificate(args.service_account))
    user = auth.get_user(args.uid)
    claims = dict(user.custom_claims or {})
    claims["announcementAdmin"] = True
    auth.set_custom_user_claims(args.uid, claims)
    print("已授予公告管理权限。请让管理员在网页中刷新权限。")


if __name__ == "__main__":
    main()
