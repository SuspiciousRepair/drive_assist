#!/usr/bin/env python3
"""Freeze openWakeWord's dynamic mel model to the streaming 1x1760 shape."""

import sys

from ai_edge_litert.tools import flatbuffer_utils


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(f"usage: {sys.argv[0]} INPUT.tflite OUTPUT.tflite")
    model = flatbuffer_utils.read_model(sys.argv[1])
    graph = model.subgraphs[0]
    input_tensor = graph.tensors[graph.inputs[0]]
    output_tensor = graph.tensors[graph.outputs[0]]
    input_tensor.shape = [1, 1760]
    input_tensor.shapeSignature = [1, 1760]
    output_tensor.shape = [1, 1, 8, 32]
    output_tensor.shapeSignature = [1, 1, 8, 32]
    flatbuffer_utils.write_model(model, sys.argv[2])


if __name__ == "__main__":
    main()
