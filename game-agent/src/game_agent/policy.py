from __future__ import annotations

from dataclasses import dataclass

from .adb import Frame
from .memory import ActionMemory, MemoryStore


@dataclass(frozen=True)
class Suggestion:
    x: int
    y: int
    confidence: float
    distance: int
    samples: int


class DemonstrationPolicy:
    """Replay a taught tap only when the visual state is familiar and well supported."""

    def __init__(self, memory: MemoryStore, max_distance: int = 8, min_samples: int = 3) -> None:
        self.memory = memory
        self.max_distance = max_distance
        self.min_samples = min_samples

    def suggest(self, game_id: str, frame: Frame) -> Suggestion | None:
        action: ActionMemory | None = self.memory.nearest_action(game_id, frame.average_hash)
        if action is None or (action.width, action.height) != (frame.width, frame.height):
            return None
        distance = (int(action.state_hash, 16) ^ int(frame.average_hash, 16)).bit_count()
        if distance > self.max_distance or action.samples < self.min_samples:
            return None
        visual_similarity = 1.0 - distance / 64.0
        repeat_support = min(1.0, action.samples / self.min_samples)
        confidence = visual_similarity * repeat_support
        return Suggestion(action.x, action.y, confidence, distance, action.samples)
