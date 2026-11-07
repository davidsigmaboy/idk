import json, os, sys, tempfile, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import umbra_host

HERE = os.path.dirname(__file__)


def call(host, **req):
    r = json.loads(host.handle(json.dumps(req)))
    assert r["ok"], r
    return r["result"]


class HostTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.host = umbra_host.Host(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_metadata_and_list(self):
        pid = call(self.host, op="load", path=os.path.join(HERE, "hello.plugin"))["id"]
        self.assertEqual(pid, "hello_world")
        listed = call(self.host, op="list")
        self.assertEqual(listed[0]["name"], "Hello World")
        self.assertEqual(listed[0]["author"], "test")

    def test_send_hook_modify(self):
        call(self.host, op="load", path=os.path.join(HERE, "hello.plugin"))
        out = call(self.host, op="send", account=0, params={"message": ".hello Maria"})
        self.assertEqual(out["params"]["message"], "Hello, Maria!")
        self.assertFalse(out["cancel"])
        out = call(self.host, op="send", params={"message": "plain text"})
        self.assertEqual(out["params"]["message"], "plain text")

    def test_settings_form_and_persistence(self):
        call(self.host, op="load", path=os.path.join(HERE, "hello.plugin"))
        form = call(self.host, op="settings", id="hello_world")
        self.assertEqual([f["type"] for f in form], ["header", "input", "text"])
        self.assertEqual(form[1]["value"], "Hello, {name}!")
        call(self.host, op="set_setting", id="hello_world", key="template", value="Hi {name}")
        out = call(self.host, op="send", params={"message": ".hello Ann"})
        self.assertEqual(out["params"]["message"], "Hi Ann")
        host2 = umbra_host.Host(self.tmp.name)  # settings survive a restart
        call(host2, op="load", path=os.path.join(HERE, "hello.plugin"))
        self.assertEqual(call(host2, op="send", params={"message": ".hello Bo"})["params"]["message"], "Hi Bo")

    def test_update_hook_cancel(self):
        call(self.host, op="load", path=os.path.join(HERE, "wordblock.plugin"))
        bad = call(self.host, op="update", name="TL_updateNewMessage", update={"message": {"message": "Buy CASINO now"}})
        self.assertTrue(bad["cancel"])
        good = call(self.host, op="update", name="TL_updateNewMessage", update={"message": {"message": "hello"}})
        self.assertFalse(good["cancel"])
        call(self.host, op="set_setting", id="word_block", key="enabled", value=False)
        again = call(self.host, op="update", name="TL_updateNewMessage", update={"message": {"message": "casino"}})
        self.assertFalse(again["cancel"])

    def test_priority_and_chain(self):
        call(self.host, op="load", path=os.path.join(HERE, "hello.plugin"))
        call(self.host, op="load", path=os.path.join(HERE, "wordblock.plugin"))
        out = call(self.host, op="send", params={"message": ".hello  Eve"})
        self.assertEqual(out["params"]["message"], "Hello, Eve!")  # word_block (prio 5) first, then hello

    def test_sync_folder(self):
        import shutil
        folder = os.path.join(self.tmp.name, "plugins")
        os.makedirs(folder)
        r = call(self.host, op="sync", dir=folder)
        self.assertEqual(r["count"], 0)
        shutil.copy(os.path.join(HERE, "hello.plugin"), folder)
        open(os.path.join(folder, "broken.plugin"), "w").write("def (:")
        r = call(self.host, op="sync", dir=folder)
        self.assertEqual(r["count"], 1)
        self.assertIn("broken.plugin", r["errors"])
        self.assertEqual(call(self.host, op="send", params={"message": ".hello Z"})["params"]["message"], "Hello, Z!")
        os.remove(os.path.join(folder, "hello.plugin"))
        self.assertEqual(call(self.host, op="sync", dir=folder)["count"], 0)

    def test_errors_do_not_crash(self):
        bad = json.loads(self.host.handle(json.dumps({"op": "load", "path": "/nonexistent.plugin"})))
        self.assertFalse(bad["ok"])
        self.assertFalse(json.loads(self.host.handle("not json"))["ok"])


if __name__ == "__main__":
    unittest.main()
