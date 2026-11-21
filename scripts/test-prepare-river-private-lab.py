"""Offline adversarial tests for fail-closed River lab staging (no Docker mutation)."""
import ast
import importlib.util
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("prepare-river-private-lab.py")
spec = importlib.util.spec_from_file_location("director_river_qa", SCRIPT)
lab = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lab)


def valid_inspect():
    return {
        "Name": "/" + lab.CONTAINER,
        "State": {"Running": True},
        "NetworkSettings": {"Ports": {}},
        "HostConfig": {
            "PortBindings": {},
            "Memory": 10 * 1024 ** 3,
            "NanoCpus": 6_000_000_000,
            "CpuQuota": 0,
            "CpuPeriod": 0,
            "NetworkMode": "bridge",
            "Privileged": False,
        },
        "Mounts": [{
            "Type": "volume", "Name": lab.CONTAINER,
            "Destination": "/workspace",
        }],
    }


class RiverQaGuards(unittest.TestCase):
    def run_inspect(self, mutate=None):
        state = valid_inspect()
        if mutate is not None:
            mutate(state)
        with patch.object(lab, "process", return_value=json.dumps([state])):
            return lab.assert_safe_lab()

    def test_valid_sandbox(self):
        self.assertEqual(self.run_inspect()["publishedPorts"], 0)

    def test_public_binding_blocked(self):
        with self.assertRaisesRegex(RuntimeError, "exposed host ports"):
            self.run_inspect(lambda x: x["HostConfig"].update(
                PortBindings={"25565/tcp": [{"HostIp": "0.0.0.0", "HostPort": "15590"}]}))

    def test_published_port_blocked(self):
        with self.assertRaisesRegex(RuntimeError, "Ports published"):
            self.run_inspect(lambda x: x["NetworkSettings"].update(
                Ports={"25565/tcp": [{"HostIp": "127.0.0.1", "HostPort": "15590"}]}))

    def test_host_network_blocked(self):
        with self.assertRaisesRegex(RuntimeError, "networking mode"):
            self.run_inspect(lambda x: x["HostConfig"].update(NetworkMode="host"))

    def test_privileged_blocked(self):
        with self.assertRaisesRegex(RuntimeError, "Privileged"):
            self.run_inspect(lambda x: x["HostConfig"].update(Privileged=True))

    def test_bind_mount_blocked(self):
        with self.assertRaisesRegex(RuntimeError, "mount"):
            self.run_inspect(lambda x: x["Mounts"].append({
                "Type": "bind", "Source": "C:\\Users",
                "Destination": "/private"}))

    def test_wrong_docker_identity_blocked(self):
        with self.assertRaisesRegex(RuntimeError, "identity"):
            self.run_inspect(lambda x: x.update(Name="/another-container"))

    def test_more_than_six_cpus_blocked(self):
        with self.assertRaisesRegex(RuntimeError, "6 CPU"):
            self.run_inspect(lambda x: x["HostConfig"].update(NanoCpus=7_000_000_000))

    def test_more_than_ten_gib_blocked(self):
        with self.assertRaisesRegex(RuntimeError, "RAM"):
            self.run_inspect(lambda x: x["HostConfig"].update(
                Memory=11 * 1024 ** 3))

    def test_stopped_container_blocked(self):
        with self.assertRaisesRegex(RuntimeError, "stopped"):
            self.run_inspect(lambda x: x["State"].update(Running=False))

    def test_duplicate_mod_filename_blocked(self):
        with patch.object(lab, "docker", return_value=(
            "0a file.jar\n0b file.jar")):
            with self.assertRaisesRegex(RuntimeError, "Duplicate"):
                lab.linux_mod_hashes("/workspace/safe")

    def test_sha_guard_not_optimized_out(self):
        with self.assertRaisesRegex(RuntimeError, "mismatch"):
            lab.require(False, "Pinned Director JAR mismatch")

    def test_server_command_uses_qualified_revision(self):
        with patch.object(lab, "LINUX_JAR",
                          "/workspace/revisions/43cccbd/build/libs/proutlost-director-1.0.0.jar"):
            with patch.object(lab, "LAB_ID", "riverqa-20261102-r43"):
                command = lab.pinned_server_command()
        self.assertIn("cd /workspace/revisions/43cccbd", command)
        self.assertIn("-PdirectorLabServerId=riverqa-20261102-r43", command)
        self.assertIn("--no-configuration-cache", command)
        self.assertNotIn("/workspace/revisions/b236971", command)

    def test_server_command_refuses_unpinned_path(self):
        with patch.object(lab, "LINUX_JAR",
                          "/workspace/revisions/43cccbd/../../private/build/libs/proutlost-director-1.0.0.jar"):
            with self.assertRaisesRegex(RuntimeError, "Unsafe pinned Linux"):
                lab.pinned_server_command()

    def test_server_command_refuses_unsafe_lab_identifier(self):
        with patch.object(lab, "LAB_ID", "riverqa;unsafe"):
            with self.assertRaisesRegex(RuntimeError, "Unsafe QA laboratory"):
                lab.pinned_server_command()

    def test_no_python_assert_in_safety_script(self):
        program = ast.parse(SCRIPT.read_text(encoding="utf-8"))
        self.assertFalse(any(isinstance(n, ast.Assert) for n in ast.walk(program)))

    def test_obsolete_staging_blocked_before_docker_access(self):
        with patch.object(sys, "argv", [str(SCRIPT), "--prepare"]):
            with patch.object(lab, "process") as no_process:
                with self.assertRaisesRegex(RuntimeError, "Obsolete b236971"):
                    lab.main()
        no_process.assert_not_called()

    def test_current_pin_gate_fails_closed_on_missing_status(self):
        with patch.object(lab, "STATUS_FILE", Path(r"C:\WorldDirectorS2\handoff\missing_fake_status_do_not_create.json")):
            with self.assertRaisesRegex(RuntimeError, "status is unavailable"):
                lab.require_current_session_pin()


if __name__ == "__main__":
    unittest.main(verbosity=2)
