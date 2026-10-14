import unittest
from unittest.mock import Mock

from game_agent.adb import AdbDevice, AdbError


class AdbSafetyTests(unittest.TestCase):
    def test_foreground_mismatch_stops_before_any_tap(self) -> None:
        device = AdbDevice("emulator-test")
        device._run = Mock(return_value=(
            b"topResumedActivity=ActivityRecord{1 u0 allowed.game/.MainActivity t1}"
        ))
        with self.assertRaisesRegex(AdbError, "未发送任何触控"):
            device.require_game_foreground("another.game")
        self.assertEqual(device._run.call_count, 1)

    def test_tap_is_scoped_to_the_selected_adb_device(self) -> None:
        device = AdbDevice("emulator-test", "adb-test")
        device._run = Mock(return_value=b"")
        device.tap(123, 456)
        device._run.assert_called_once_with("shell", "input", "tap", "123", "456")


if __name__ == "__main__":
    unittest.main()
