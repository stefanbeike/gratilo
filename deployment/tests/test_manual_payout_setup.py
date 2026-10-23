import importlib.util
import io
import os
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('payout_setup', ROOT / 'deployment/scripts/prepare_manual_payout_accounts.py')
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class Fake:
    def __init__(self):
        self.schedule = 'daily'
        self.writes = []
        self.confirm = True
        self.fail = False
        self.bank = True
        self.data = {'id': 'acct_fixture', 'object': 'v2.core.account', 'livemode': False, 'dashboard': 'express',
                     'configuration': {'recipient': {'capabilities': {'stripe_balance': {'payouts': {'status': 'active'}}}}},
                     'requirements': None}

    def call(self, path, account=None, form=None):
        if path.startswith('/v2/'):
            return self.data
        if '/external_accounts' in path:
            return {'data': [{'object': 'bank_account', 'currency': 'eur', 'default_for_currency': True,
                              'status': 'verified'}] if self.bank else []}
        if path.startswith('/v1/accounts?'):
            return {'data': [{'id': 'acct_fixture', 'controller': {'is_controller': True}}], 'has_more': False}
        assert path == '/v1/balance_settings', 'No payout endpoint allowed'
        if form is not None:
            if self.fail:
                raise setup.StripeError('permission_denied', 'Platform cannot manage payout schedules.', 'req_fixture')
            self.writes.append((account, form))
            if self.confirm:
                self.schedule = 'manual'
        return {'payments': {'payouts': {'status': 'enabled', 'schedule': {'interval': self.schedule}}}}


class ManualPayoutSetupTest(unittest.TestCase):
    def test_default_dry_run_only_reads_and_reports_planned_change(self):
        client = Fake()
        self.assertEqual('WOULD_CONFIGURE', list(setup.run(client))[0]['status'])
        self.assertEqual([], client.writes)

    def test_optional_account_filter_is_respected_but_never_required(self):
        client=Fake()
        self.assertEqual('OPTIONAL_TEST_FILTER',list(setup.run(client,True,{'acct_other'}))[0]['code'])
        self.assertEqual([],client.writes)
        self.assertEqual('WOULD_CONFIGURE',list(setup.run(client,False,set()))[0]['status'])

    def test_apply_verifies_and_repeated_run_never_rewrites_manual(self):
        client = Fake()
        for _ in range(2):
            self.assertEqual('READY', list(setup.run(client, True))[0]['status'])
        self.assertEqual([('acct_fixture', {'payments[payouts][schedule][interval]': 'manual'})], client.writes)

    def test_unconfirmed_post_is_not_success(self):
        client = Fake()
        client.confirm = False
        self.assertEqual('VERIFY_PENDING', list(setup.run(client, True))[0]['status'])

    def test_exact_permission_failure_and_request_id_are_reported(self):
        client = Fake()
        client.fail = True
        result = list(setup.run(client, True))[0]
        self.assertEqual('STRIPE_REJECTED', result['status'])
        self.assertEqual('permission_denied', result['code'])
        self.assertEqual('req_fixture', result['requestId'])
        self.assertIn('cannot manage', result['message'])

    def test_missing_bank_unready_live_and_incomplete_accounts_do_not_write(self):
        for variant in ('bank', 'live', 'null', 'incomplete'):
            client = Fake()
            if variant == 'bank': client.bank = False
            if variant == 'live': client.data['livemode'] = True
            if variant == 'null': client.data['configuration'] = None
            if variant == 'incomplete': del client.data['requirements']
            self.assertNotEqual('READY', list(setup.run(client, True))[0]['status'])
            self.assertEqual([], client.writes)

    def test_client_never_allows_live_keys_payout_posts_or_implicit_settings_posts(self):
        for key in ('sk_live_fixture', '', 'rk_test_fixture'):
            with self.assertRaises(ValueError): setup.Client(key)
        for apply in (True, False):
            client = setup.Client('sk_test_fixture', apply)
            with self.assertRaises(ValueError): client.call('/v1/payouts', 'acct_fixture', {'amount': 100})
        with self.assertRaises(ValueError): setup.Client('sk_test_fixture').call('/v1/balance_settings', 'acct_fixture', {'payments[payouts][schedule][interval]': 'manual'})

    def test_apply_without_separate_confirmation_makes_no_calls(self):
        with patch.dict(os.environ, {'STRIPE_SECRET_KEY': 'sk_test_fixture'}, clear=True), patch('sys.argv', ['setup', '--apply']), patch.object(setup, 'Client') as client, patch('sys.stderr', io.StringIO()):
            with self.assertRaises(SystemExit): setup.main()
            client.assert_not_called()

    def test_error_messages_never_reveal_keys_emails_or_iban(self):
        result = setup.safe_message('sk_test_private test@example.test DE89370400440532013000')
        for secret in ('private', 'test@example', 'DE893'): self.assertNotIn(secret, result)

    def test_pagination_discovers_accounts_without_manual_ids_and_skips_foreign_ones(self):
        class Pages(Fake):
            def call(self, path, account=None, form=None):
                if path == '/v1/accounts?limit=100':
                    return {'data': [{'id': 'acct_foreign', 'controller': {'is_controller': False}}], 'has_more': True}
                if path == '/v1/accounts?limit=100&starting_after=acct_foreign':
                    return {'data': [{'id': 'acct_fixture', 'controller': {'is_controller': True}}], 'has_more': False}
                return super().call(path, account, form)
        result = list(setup.run(Pages()))
        self.assertEqual(['SKIPPED', 'WOULD_CONFIGURE'], [r['status'] for r in result])


if __name__ == '__main__':
    unittest.main()
