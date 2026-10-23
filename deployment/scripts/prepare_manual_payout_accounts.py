"""Prepare existing platform-owned Express test accounts; default is GET-only dry-run.

No database/AWS access and no Payout API. Applying settings needs explicit flags
and --apply --confirm 'CONFIGURE STRIPE TEST PAYOUT SCHEDULES'.
"""
import argparse
import json
import os
import re
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

VERSION = "2026-09-30.endive"
CONFIRMATION = "CONFIGURE STRIPE TEST PAYOUT SCHEDULES"


def mapping(value):
    return value if isinstance(value, dict) else {}


def safe_message(value):
    value = re.sub(r"(?:sk|rk|pk)_(?:test|live)_[A-Za-z0-9]+", "[secret]", str(value))
    value = re.sub(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}", "[email]", value)
    return re.sub(r"\b[A-Z]{2}[0-9]{2}[A-Z0-9]{10,30}\b", "[bank]", value).replace("\n", " ")[:400]


class StripeError(Exception):
    def __init__(self, code, message, request_id=""):
        super().__init__(safe_message(message))
        self.code = code if isinstance(code,str) and re.fullmatch(r"[A-Za-z0-9_.-]{1,100}",code) else "stripe_rejected"
        self.request_id = request_id if isinstance(request_id,str) and re.fullmatch(r"req_[A-Za-z0-9]+",request_id) else ""


class Client:
    def __init__(self, key, apply=False):
        if not re.fullmatch(r"sk_test_[A-Za-z0-9]+", key or ""):
            raise ValueError("A Stripe test secret key is required; live/restricted keys are not accepted")
        self.key, self.apply = key, apply

    def call(self, path, account=None, form=None):
        if not path.startswith("/v1/") and not path.startswith("/v2/core/accounts/"):
            raise ValueError("Unsupported Stripe path")
        if form is not None and (not self.apply or path != "/v1/balance_settings" or
                                 form != {"payments[payouts][schedule][interval]": "manual"}):
            raise ValueError("Only explicitly approved manual-schedule updates are allowed")
        headers = {"Authorization": "Bearer " + self.key, "Stripe-Version": VERSION}
        if account:
            if not re.fullmatch(r"acct_[A-Za-z0-9]+", account):
                raise ValueError("Invalid account")
            headers["Stripe-Account"] = account
        data = None if form is None else urlencode(form).encode()
        if data is not None:
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        try:
            with urlopen(Request("https://api.stripe.com" + path, data=data, headers=headers,
                                 method="GET" if data is None else "POST"), timeout=20) as response:
                return json.load(response)
        except HTTPError as error:
            try:
                detail = json.load(error).get("error", {})
            except (ValueError, AttributeError):
                detail = {}
            raise StripeError(detail.get("code") or detail.get("type") or "stripe_rejected",
                              detail.get("message", "Stripe rejected the operation"),
                              error.headers.get("Request-Id", "")) from None


def prepare(client, account, apply=False):
    query = urlencode({"include[0]": "configuration.merchant", "include[1]": "configuration.recipient",
                       "include[2]": "requirements", "include[3]": "defaults"})
    data = client.call("/v2/core/accounts/" + account + "?" + query)
    if data.get("id") != account or data.get("object") != "v2.core.account" or data.get("livemode") is not False:
        return {"account": account, "status": "SKIPPED", "code": "TEST_V2_ACCOUNT_REQUIRED"}
    if data.get("dashboard") != "express" or data.get("closed") not in (None, False):
        return {"account": account, "status": "SKIPPED", "code": "EXPRESS_ACCOUNT_REQUIRED"}
    recipient = mapping(mapping(data.get("configuration")).get("recipient"))
    payouts = mapping(mapping(mapping(recipient.get("capabilities")).get("stripe_balance")).get("payouts"))
    requirements = mapping(data.get("requirements"))
    deadline = mapping(mapping(requirements.get("summary")).get("minimum_deadline")).get("status")
    if payouts.get("status") != "active" or "requirements" not in data or deadline in ("currently_due", "past_due"):
        return {"account": account, "status": "NOT_READY", "code": "PAYOUTS_NOT_ENABLED"}
    banks = client.call("/v1/accounts/" + account + "/external_accounts?object=bank_account&limit=100", account)
    eligible = [b for b in banks.get("data", []) if b.get("object") == "bank_account" and
                b.get("currency") == "eur" and b.get("default_for_currency") is True and
                b.get("status") in ("new", "validated", "verified")]
    if banks.get("has_more") or len(eligible) != 1:
        return {"account": account, "status": "NOT_READY", "code": "EUR_BANK_REQUIRED"}
    settings = client.call("/v1/balance_settings", account)
    payout_settings = mapping(mapping(settings.get("payments")).get("payouts"))
    schedule = mapping(payout_settings.get("schedule")).get("interval")
    if payout_settings.get("status") != "enabled":
        return {"account": account, "status": "NOT_READY", "code": "PAYOUTS_DISABLED"}
    if schedule == "manual":
        return {"account": account, "status": "READY", "schedule": schedule}
    if not apply:
        return {"account": account, "status": "WOULD_CONFIGURE", "schedule": schedule}
    client.call("/v1/balance_settings", account, {"payments[payouts][schedule][interval]": "manual"})
    settings = client.call("/v1/balance_settings", account)
    verified = mapping(mapping(settings.get("payments")).get("payouts"))
    ready = mapping(verified.get("schedule")).get("interval") == "manual" and verified.get("status") == "enabled"
    return {"account": account, "status": "READY" if ready else "VERIFY_PENDING",
            "schedule": mapping(verified.get("schedule")).get("interval")}


def run(client, apply=False, allowed_accounts=None):
    after = ""
    seen = set()
    while True:
        page = client.call("/v1/accounts?limit=100" + after)
        for account in page.get("data", []):
            account_id = account.get("id", "")
            if account_id in seen or not re.fullmatch(r"acct_[A-Za-z0-9]+", account_id):
                raise ValueError("Invalid/duplicate account page")
            seen.add(account_id)
            if allowed_accounts and account_id not in allowed_accounts:
                yield {"account": account_id, "status": "SKIPPED", "code": "OPTIONAL_TEST_FILTER"}
                continue
            if mapping(account.get("controller")).get("is_controller") is not True:
                yield {"account": account_id, "status": "SKIPPED", "code": "NOT_PLATFORM_CONTROLLED"}
                continue
            try:
                yield prepare(client, account_id, apply)
            except StripeError as error:
                yield {"account": account_id, "status": "STRIPE_REJECTED", "code": error.code,
                       "requestId": error.request_id, "message": str(error)}
            except Exception:
                yield {"account": account_id, "status": "UNAVAILABLE", "code": "STRIPE_READ_FAILED"}
        if not page.get("has_more"):
            break
        if not page.get("data"):
            raise ValueError("Incomplete account page")
        after = "&starting_after=" + page["data"][-1]["id"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--apply", action="store_true")
    mode.add_argument("--dry-run", action="store_true")
    parser.add_argument("--confirm", default="")
    args = parser.parse_args()
    if args.apply and (args.confirm != CONFIRMATION or
                       os.environ.get("STRIPE_MANUAL_PAYOUTS_API_APPROVED") != "true" or
                       os.environ.get("STRIPE_MANUAL_PAYOUT_SETUP_ENABLED") != "true" or
                       os.environ.get("STRIPE_SANDBOX_PAYOUTS") != "false"):
        parser.error("Apply requires the explicit test-mode flags and confirmation; no calls made")
    raw_filter=os.environ.get("STRIPE_MANUAL_PAYOUT_APPROVED_ACCOUNTS","")
    allowed={value.strip() for value in raw_filter.split(",")} if raw_filter.strip() else set()
    if any(not re.fullmatch(r"acct_[A-Za-z0-9]+",value) for value in allowed):
        parser.error("Invalid optional account filter; no calls made")
    client = Client(os.environ.get("STRIPE_SECRET_KEY", ""), args.apply)
    for result in run(client, args.apply, allowed):
        from datetime import datetime, timezone
        result["checkedAt"]=datetime.now(timezone.utc).isoformat()
        print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
