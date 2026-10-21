"""Persistent RapidOCR worker for the opt-in live Lua regression.

stdin:  IMAGE<TAB>base64(UTF-8 absolute PNG path)
stdout: ROW<TAB>base64(UTF-8 text)<TAB>x<TAB>y<TAB>confidence, then END
        or ERROR<TAB>base64(UTF-8 diagnostic), then END.
"""

import base64
from contextlib import redirect_stdout
import sys

from rapidocr import RapidOCR


def encoded(value):
    return base64.b64encode(str(value).encode("utf-8")).decode("ascii")


def main():
    # The package defaults both ONNX Runtime pools to -1 (all available CPUs).
    # Under the emulator that can starve the next full-frame OCR request.
    params = {
        "EngineConfig.onnxruntime.intra_op_num_threads": 2,
        "EngineConfig.onnxruntime.inter_op_num_threads": 1,
    }
    with redirect_stdout(sys.stderr):
        recognizer = RapidOCR(params=params)
    print("READY", flush=True)
    for request in sys.stdin:
        try:
            command, path64 = request.rstrip("\r\n").split("\t", 1)
            if command != "IMAGE":
                raise ValueError("unsupported OCR command")
            path = base64.b64decode(path64, validate=True).decode("utf-8")
            with redirect_stdout(sys.stderr):
                result = recognizer(path)
            boxes = result.boxes if result.boxes is not None else []
            texts = result.txts if result.txts is not None else []
            scores = result.scores if result.scores is not None else []
            if not (len(boxes) == len(texts) == len(scores)):
                raise RuntimeError("RapidOCR returned mismatched row arrays")
            for box, value, score in zip(boxes, texts, scores):
                x = sum(float(point[0]) for point in box) / len(box)
                y = sum(float(point[1]) for point in box) / len(box)
                print(f"ROW\t{encoded(value)}\t{x:.4f}\t{y:.4f}\t{float(score):.6f}")
        except Exception as error:
            print(f"ERROR\t{encoded(type(error).__name__ + ': ' + str(error))}")
        print("END", flush=True)


if __name__ == "__main__":
    main()
