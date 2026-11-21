"""No-write tests of two-account QA whitelist/OP4 configuration."""
import ast
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("configure-river-private-lab-players.py")
spec = importlib.util.spec_from_file_location("director_qa_players", SCRIPT)
users = importlib.util.module_from_spec(spec)
spec.loader.exec_module(users)

ONE = "e968caba-3fee-42d2-a77b-2dce82760a5c"
TWO = "6b25b2fb-7256-42fd-a251-f57b3014c348"


class QaPlayerGuards(unittest.TestCase):
    def test_two_distinct_accounts_op4(self):
        roles = users.role_files("TesterA", ONE, "RiverTest", TWO)
        allow = json.loads(roles["whitelist.json"])
        ops = json.loads(roles["ops.json"])
        self.assertEqual(len(allow), 2)
        self.assertEqual([x["name"] for x in allow], ["TesterA", "RiverTest"])
        self.assertEqual(len(ops), 1)
        self.assertEqual(ops[0]["level"], 4)
        self.assertEqual(ops[0]["name"], "TesterA")
        self.assertIs(ops[0]["bypassesPlayerLimit"], False)
        self.assertNotIn(allow[1]["uuid"], [p["uuid"] for p in ops])

    def test_deterministic_bytes(self):
        self.assertEqual(users.role_files("TesterA", ONE, "RiverTest", TWO),
                         users.role_files("TesterA", ONE, "RiverTest", TWO))

    def test_same_uuid_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "share a Minecraft UUID"):
            users.role_files("TesterA", ONE, "RiverTest", ONE)

    def test_same_name_casefold_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "share a Minecraft name"):
            users.role_files("TesterA", ONE, "testera", TWO)

    def test_path_in_user_name_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "name must"):
            users.role_files("../escape", ONE, "RiverTest", TWO)

    def test_invalid_uuid_rejected(self):
        with self.assertRaisesRegex(ValueError, "Invalid Minecraft"):
            users.role_files("TesterA", "invalid", "RiverTest", TWO)

    def test_zero_uuid_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "All-zero UUID"):
            users.role_files("TesterA", "00000000-0000-0000-0000-000000000000",
                             "RiverTest", TWO)

    def test_no_optimized_out_guards(self):
        program = ast.parse(SCRIPT.read_text(encoding="utf-8"))
        self.assertFalse(any(isinstance(n, ast.Assert) for n in ast.walk(program)))

    def test_missing_accounts_never_apply(self):
        with patch.object(sys, "argv", [str(SCRIPT), "--apply"]):
            with self.assertRaisesRegex(RuntimeError, "four verified"):
                users.main()

    def test_partial_accounts_never_stage(self):
        with patch.object(sys, "argv", [str(SCRIPT), "--operator-name", "TesterA"]):
            with self.assertRaisesRegex(RuntimeError, "all four"):
                users.main()

    def test_qualified_43cccbd_manifest_matches_staged_copy(self):
        # Real QA artifact validation, no world edits, Docker not required.
        self.assertEqual(users.PIN, "43cccbdc6385263b63c571ab1c8b01143c0fbce1")
        self.assertTrue(users.STAGE.is_dir())
        self.assertEqual(users.RECEIPT_SHA, users.lab.sha_file(users.RECEIPT))
        self.assertEqual(users.CORRECTION_SHA, users.lab.sha_file(users.CORRECTION))
        self.assertEqual(users.JAR_SHA, users.lab.sha_file(users.JAR))
        receipt = json.loads(users.RECEIPT.read_text(encoding="utf-8"))
        self.assertEqual(receipt["serverGameDirectory"], users.TARGET)
        self.assertEqual(receipt["sourcePackSha256"], users.PACK_SHA)
        self.assertEqual(receipt["modsSha256"], users.pack_mods())
        self.assertEqual(users.lab.sha_file(users.STAGE / "server.properties"),
                         users.STAGED_PROPERTIES_SHA)
        # The older receipt recorded intended LF bytes, not actual Windows
        # CRLF bytes; never falsely claim those hashes are identical.
        self.assertNotEqual(receipt["serverPropertiesSha256"],
                            users.STAGED_PROPERTIES_SHA)
        self.assertNotIn("b236971", users.TARGET)
        self.assertNotIn("b236971", str(users.PLAYER_RECEIPT))
        self.assertFalse(users.PLAYER_RECEIPT.exists(),
                         "No real accounts should be staged before the session")

    def test_stale_legacy_stage_receipt_refused(self):
        legacy = users.KIT / "SERVER_PRIVATE_STAGE_RECEIPT.json"
        self.assertTrue(legacy.is_file())
        with patch.object(users, "RECEIPT", legacy):
            with self.assertRaisesRegex(RuntimeError, "43cccbd stage receipt"):
                users.preflight()

    def test_tampered_launch_correction_refused(self):
        with patch.object(users, "CORRECTION_SHA", "0" * 64):
            with self.assertRaisesRegex(RuntimeError, "corrected launch"):
                users.preflight()

    def test_reviewed_lf_attributes_pin_passes(self):
        with patch.object(users.lab, "process",
                          return_value=users.APPROVED_LF_ATTR_BLOB) as check:
            users.verify_reviewed_lf_attributes({".gitattributes"})
        self.assertEqual(check.call_args.args[0][-1], "HEAD:.gitattributes")

    def test_reviewed_attributes_are_only_lf_rules_on_top_of_pin(self):
        self.assertEqual(users.APPROVED_LF_ATTR_BLOB,
                         "cc30880a3737bab2fa6cf089ba33440a7eefd576")
        actual = users.lab.process(["git", "-C", str(users.lab.REPO),
                                    "rev-parse", "HEAD:.gitattributes"])
        self.assertEqual(actual, users.APPROVED_LF_ATTR_BLOB)
        original = subprocess.check_output(
            ["git", "-C", str(users.lab.REPO), "show",
             users.PIN + ":.gitattributes"]).decode().splitlines()
        current = subprocess.check_output(
            ["git", "-C", str(users.lab.REPO), "show",
             "HEAD:.gitattributes"]).decode().splitlines()
        self.assertEqual(current[:len(original)], original)
        added = current[len(original):]
        self.assertTrue(added)
        for line in added:
            if line.strip() and not line.startswith("#"):
                self.assertTrue(line.endswith(" text eol=lf"), line)

    def test_unreviewed_lf_attributes_fail_closed(self):
        with patch.object(users.lab, "process", return_value="0" * 40):
            with self.assertRaisesRegex(RuntimeError, "Unreviewed .gitattributes"):
                users.verify_reviewed_lf_attributes({".gitattributes"})

    def test_no_attribute_change_requires_no_git_read(self):
        with patch.object(users.lab, "process", side_effect=AssertionError):
            users.verify_reviewed_lf_attributes(set())

    def test_exact_reviewed_doc_blob_is_eligible_for_frozen_qa(self):
        path = "docs/worlddirector-v1/J05_BLOCK_OWNERSHIP_RECOVERY_FENCE_20261102.md"
        expected = "64fa1ec733b49eb5623b27da1d0833307e0a4e1a"
        self.assertEqual(users.REVIEWED_DOC_BLOBS, {path: expected})
        with patch.object(users.lab, "process", return_value=expected) as git:
            users.verify_reviewed_docs({path})
        self.assertEqual(git.call_args.args[0][-1], "HEAD:" + path)

    def test_changed_reviewed_doc_blob_is_rejected(self):
        path = next(iter(users.REVIEWED_DOC_BLOBS))
        with patch.object(users.lab, "process", return_value="0" * 40):
            with self.assertRaisesRegex(RuntimeError, "Unreviewed documentation"):
                users.verify_reviewed_docs({path})

    def test_other_docs_are_not_blanket_whitelisted(self):
        self.assertNotIn("docs/worlddirector-v1/OTHER.md", users.REVIEWED_DOC_BLOBS)
        self.assertFalse(users.pinned_source_path("docs/worlddirector-v1/OTHER.md"))
        with patch.object(users.lab, "process", side_effect=AssertionError):
            users.verify_reviewed_docs({"docs/worlddirector-v1/OTHER.md"})

    def test_pinned_linux_source_crlf_and_lf_equivalent(self):
        path = "src/director/java/fr/proutlost/director/runtime/DurableDisasterRunner.java"
        with patch.object(users.subprocess, "check_output",
                          side_effect=[path.encode() + b"\n",
                                       b"unchanged\r\n", b"unchanged\n"]) as check:
            users.verify_frozen_linux_sources({path})
        self.assertEqual(check.call_count, 3)
        self.assertIn("43cccbd", check.call_args.args[0][-1])

    def test_pinned_linux_source_tampering_refused(self):
        path = "src/director/java/fr/proutlost/director/runtime/DurableDisasterRunner.java"
        with patch.object(users.subprocess, "check_output",
                          side_effect=[path.encode() + b"\n",
                                       b"qualified\n", b"unqualified\n"]):
            with self.assertRaisesRegex(RuntimeError, "Pinned Linux source differs"):
                users.verify_frozen_linux_sources({path})

    def test_unreviewed_source_never_whitelisted(self):
        with self.assertRaisesRegex(RuntimeError, "Unreviewed code"):
            users.verify_frozen_linux_sources(
                {"src/main/java/fr/proutlost/worldprep/WorldPrepConfig.java"})

    def test_path_traversal_never_accepted(self):
        for path in ("", "../build.gradle", "src/director/java/../../main.java",
                     "src/director/java/./Mirror.java", "src/director/java/../Mirror.java",
                     "src/director/java/evil\\file.java"):
            self.assertFalse(users.pinned_source_path(path), path)

    def test_build_script_must_match_frozen_original(self):
        with patch.object(users.subprocess, "check_output",
                          side_effect=[b"build.gradle\n",
                                       b"build rule\r\n", b"build rule\n"]) as read:
            users.verify_frozen_linux_sources({"build.gradle"})
        self.assertEqual(read.call_count, 3)

    def test_new_director_source_must_not_appear_in_old_mirror(self):
        path = "src/director/java/fr/proutlost/director/runtime/NewFutureFeature.java"
        with patch.object(users.subprocess, "check_output",
                          return_value=b"build.gradle\n"):
            with patch.object(users.subprocess, "run") as exists:
                exists.return_value.returncode = 0
                with self.assertRaisesRegex(RuntimeError, "presence/verification uncertain"):
                    users.verify_frozen_linux_sources({path})
        self.assertEqual(exists.call_args.args[0][-1],
                         "/workspace/revisions/43cccbd/" + path)

    def test_new_director_source_absent_in_old_mirror_does_not_mix(self):
        path = "src/director/java/fr/proutlost/director/runtime/NewFutureFeature.java"
        with patch.object(users.subprocess, "check_output",
                          return_value=b"build.gradle\n") as read:
            with patch.object(users.subprocess, "run") as exists:
                exists.return_value.returncode = 1
                users.verify_frozen_linux_sources({path})
        self.assertEqual(read.call_count, 1)
        self.assertEqual(exists.call_count, 1)

    def test_pinned_blob_read_error_is_never_interpreted_as_new_source(self):
        path = "src/director/java/fr/proutlost/director/runtime/DurableDisasterRunner.java"
        with patch.object(users.subprocess, "check_output",
                          side_effect=[path.encode() + b"\n",
                                       subprocess.CalledProcessError(128, "git show")]):
            with patch.object(users.subprocess, "run") as exists:
                with self.assertRaises(subprocess.CalledProcessError):
                    users.verify_frozen_linux_sources({path})
                exists.assert_not_called()

    def test_unavailable_pinned_git_tree_fails_closed(self):
        with patch.object(users.subprocess, "check_output",
                          side_effect=subprocess.CalledProcessError(128, "git ls-tree")):
            with self.assertRaises(subprocess.CalledProcessError):
                users.verify_frozen_linux_sources({"build.gradle"})

    def test_preflight_mode_never_stages_accounts(self):

        with patch.object(sys, "argv", [str(SCRIPT), "--preflight"]):
            with patch.object(users, "preflight", return_value={"modCount": 17}) as audit:
                with patch("builtins.print") as out:
                    users.main()
        audit.assert_called_once_with()
        result = json.loads(out.call_args.args[0])
        self.assertEqual(result["commit"], users.PIN)
        self.assertEqual(result["state"], "PINNED_STAGE_VERIFIED_AWAITING_REAL_CLIENTS_AND_PRIVATE_ROUTE")
        self.assertFalse(result["privateMinecraftRouteVerified"])
        self.assertFalse(result["publicPortPublished"])
        self.assertFalse(result["playersConfigured"])
        self.assertFalse(result["serverStarted"])

    def test_preflight_cannot_be_combined_with_apply(self):
        with patch.object(sys, "argv", [str(SCRIPT), "--preflight", "--apply"]):
            with self.assertRaisesRegex(RuntimeError, "cannot stage"):
                users.main()

    def test_local_role_files_are_confined_to_current_stage(self):
        import hashlib
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            payload = b"[]\\n"
            digest = hashlib.sha256(payload).hexdigest()
            with patch.object(users, "STAGE", directory):
                with patch.object(users.subprocess, "run") as run:
                    run.return_value.returncode = 0
                    with patch.object(users.lab, "docker", return_value=digest + "  file") as docker:
                        result = users.add_only_if_same("whitelist.json", payload)
            self.assertEqual(result, digest)
            self.assertEqual((directory / "whitelist.json").read_bytes(), payload)
            docker.assert_called_once_with("sha256sum", users.TARGET + "/whitelist.json")
            self.assertEqual(run.call_args.args[0][-1], users.TARGET + "/whitelist.json")
            self.assertNotIn("b236971", run.call_args.args[0][-1])

    def test_no_unsafe_file_names_for_role_copy(self):
        with self.assertRaisesRegex(RuntimeError, "Unsafe player file name"):
            users.add_only_if_same("../ops.json", b"[]")


if __name__ == "__main__":
    unittest.main(verbosity=2)
