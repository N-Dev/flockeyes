#!/usr/bin/env python3
"""Writes tests/models/pixel-embedder.onnx: a stand-in for the cow recognition model, for tests.

It takes the same input as the real model (one 224 x 224 picture, [1, 3, 224, 224]) and gives the same
kind of answer ([1, 768]): the picture averaged down to 16 x 16 x 3 = 768 numbers, less their mean. The
same picture gives the same answer and different pictures different ones, which is all the tests of the
tracking and naming logic need, without the 30 MB model or its licence.

The file is written byte by byte (ONNX is a protocol buffer), so this needs nothing but Python.
"""
import os
import struct

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(os.path.dirname(HERE), "tests", "models", "pixel-embedder.onnx")


def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def field(num, wire, payload):
    return varint((num << 3) | wire) + payload


def integer(num, v):
    return field(num, 0, varint(v))


def blob(num, b):
    if isinstance(b, str):
        b = b.encode()
    return field(num, 2, varint(len(b)) + b)


def attr_ints(name, values):
    # AttributeProto: name = 1, ints = 8, type = 20 (INTS = 7)
    return blob(1, name) + b"".join(integer(8, v) for v in values) + integer(20, 7)


def attr_int(name, value):
    # AttributeProto: name = 1, i = 3, type = 20 (INT = 2)
    return blob(1, name) + integer(3, value) + integer(20, 2)


def node(op, inputs, outputs, attrs=()):
    # NodeProto: input = 1, output = 2, name = 3, op_type = 4, attribute = 5
    return b"".join(blob(1, i) for i in inputs) + b"".join(blob(2, o) for o in outputs) + blob(3, op.lower() + "_" + outputs[0]) + blob(4, op) + \
        b"".join(blob(5, a) for a in attrs)


def value_info(name, dims):
    # ValueInfoProto: name = 1, type = 2 { tensor_type = 1 { elem_type = 1 (FLOAT = 1), shape = 2 { dim = 1 { dim_value = 1 } } } }
    shape = b"".join(blob(1, integer(1, d)) for d in dims)
    tensor = integer(1, 1) + blob(2, shape)
    return blob(1, name) + blob(2, blob(1, tensor))


def initializer_int64(name, values):
    # TensorProto: dims = 1, data_type = 2 (INT64 = 7), name = 8, raw_data = 9
    return integer(1, len(values)) + integer(2, 7) + blob(8, name) + blob(9, struct.pack("<%dq" % len(values), *values))


def main():
    nodes = [
        node("AveragePool", ["image"], ["pooled"], [attr_ints("kernel_shape", [14, 14]), attr_ints("strides", [14, 14])]),
        node("Flatten", ["pooled"], ["flat"], [attr_int("axis", 1)]),
        node("ReduceMean", ["flat"], ["mean"], [attr_ints("axes", [1]), attr_int("keepdims", 1)]),
        node("Sub", ["flat", "mean"], ["embedding"]),
    ]
    # GraphProto: node = 1, name = 2, input = 11, output = 12
    graph = b"".join(blob(1, n) for n in nodes) + blob(2, "pixel-embedder") + blob(11, value_info("image", [1, 3, 224, 224])) + \
        blob(12, value_info("embedding", [1, 768]))
    # ModelProto: ir_version = 1, producer_name = 2, graph = 7, opset_import = 8 { domain = 1, version = 2 }
    model = integer(1, 8) + blob(2, "flockeyes") + blob(7, graph) + blob(8, blob(1, "") + integer(2, 13))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as f:
        f.write(model)
    print(OUT, len(model), "bytes")


if __name__ == "__main__":
    main()
