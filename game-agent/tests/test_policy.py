import tempfile
import unittest
from pathlib import Path

from game_agent.adb import Frame
from game_agent.memory import MemoryStore
from game_agent.policy import DemonstrationPolicy


def frame(hash_value: str) -> Frame:
    return Frame(b"png", 1280, 720, hash_value)


class DemonstrationPolicyTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.memory = MemoryStore(Path(self.temp.name) / "memory.sqlite3")
        self.policy = DemonstrationPolicy(self.memory)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def teach_three(self, game: str = "authorized.game") -> None:
        for _ in range(3):
            self.memory.add_demonstration(game, frame("0000000000000000"), 120, 240,
                                          frame("0000000000000001"))

    def test_unknown_screen_never_suggests_a_tap(self) -> None:
        self.teach_three()
        self.assertIsNone(self.policy.suggest("other.game", frame("0000000000000000")))

    def test_one_or_two_examples_are_not_enough_to_replay(self) -> None:
        for _ in range(2):
            self.memory.add_demonstration("authorized.game", frame("0000000000000000"),
                                          120, 240, frame("0000000000000001"))
        self.assertIsNone(self.policy.suggest("authorized.game", frame("0000000000000000")))

    def test_repeated_familiar_state_returns_the_taught_action(self) -> None:
        self.teach_three()
        suggestion = self.policy.suggest("authorized.game", frame("0000000000000000"))
        self.assertIsNotNone(suggestion)
        self.assertEqual((suggestion.x, suggestion.y), (120, 240))
        self.assertEqual(suggestion.samples, 3)
        self.assertAlmostEqual(suggestion.confidence, 1.0)

    def test_resolution_change_disables_replay(self) -> None:
        self.teach_three()
        changed_resolution = Frame(b"png", 800, 600, "0000000000000000")
        self.assertIsNone(self.policy.suggest("authorized.game", changed_resolution))


if __name__ == "__main__":
    unittest.main()
