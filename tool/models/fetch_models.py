"""Downloads, verifies and quantises both models into the app's assets (D12).
docs/01-platform/models/lld.md §2–§3. Run through tool/models_in_docker.sh.

Pinned by content: a download whose sha256 differs stops the script, so a
replaced upstream file is noticed rather than shipped.
"""
import argparse
import hashlib
import os
import shutil
import sys
import time
import urllib.request

import numpy as np

MEDIAPIPE = dict(
    url="https://storage.googleapis.com/mediapipe-models/image_segmenter/"
        "selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite",
    cache="selfie_multiclass_256x256.tflite",
    # Pinned 2026-11-01; Google's own md5 for the object matched.
    sha256="c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0",
)
SEGFORMER = dict(
    # A commit, not main.
    url="https://huggingface.co/mattmdjaga/segformer_b2_clothes/resolve/"
        "584abc1e1d260e23c0fc627c5217a09b2b461046/onnx/model.onnx",
    cache="segformer_b2_clothes_fp32.onnx",
    sha256="a93a8dac171b5c1fcc53632a8bfc180bfd9759ea69a3e207451bb07f76add54f",
)
OUT_MEDIAPIPE = "selfie_multiclass_256x256.tflite"
OUT_SEGFORMER = "segformer_b2_clothes_int8.onnx"

MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch(spec, cache_dir):
    path = os.path.join(cache_dir, spec["cache"])
    if not os.path.exists(path):
        part = path + ".part"
        print(f"downloading {spec['url']}", flush=True)
        # Each read is bounded; the whole run is bounded by the script's timeout (D21).
        with urllib.request.urlopen(spec["url"], timeout=60) as r, open(part, "wb") as f:
            shutil.copyfileobj(r, f, 1 << 20)
        os.replace(part, path)
    got = sha256(path)
    if got != spec["sha256"]:
        os.remove(path)
        raise SystemExit(f"{spec['cache']}: sha256 {got} is not the pinned {spec['sha256']} — "
                         f"the upstream file changed. Check it, then update the pin.")
    print(f"verified {spec['cache']} ({os.path.getsize(path) / 1e6:.1f} MB)")
    return path


def quantise(src, dst):
    from onnxruntime.quantization import QuantType, quantize_dynamic

    # SegFormer's weights are 68 % MatMul and 32 % Conv, so both are quantised —
    # MatMul alone left 55 MB. Weights are *unsigned* 8-bit: ONNX Runtime's CPU
    # ConvInteger has no signed-weight kernel and the model fails to load
    # ("NOT_IMPLEMENTED ... ConvInteger"). Measured 2026-11-01 on a photo of a
    # person: 28.6 MB, 99.80 % of output cells agree with fp32 (MatMul-only s8:
    # 54.8 MB, 99.74 %). Models LLD §2.
    quantize_dynamic(src, dst, op_types_to_quantize=["MatMul", "Conv"], weight_type=QuantType.QUInt8)
    print(f"quantised: {os.path.getsize(src) / 1e6:.1f} MB -> {os.path.getsize(dst) / 1e6:.1f} MB")


def synthetic_input():
    """A fixed, person-free image: proves the model runs and the shapes are right, nothing more."""
    y, x = np.mgrid[0:512, 0:512].astype(np.float32) / 511.0
    img = np.stack([x, y, 1 - x * y], axis=-1)
    img[150:450, 180:330] = [0.7, 0.1, 0.1]
    img[60:150, 210:300] = [0.8, 0.6, 0.5]
    return img


def image_input(path):
    from PIL import Image

    img = Image.open(path).convert("RGB").resize((512, 512), Image.BILINEAR)
    return np.asarray(img, dtype=np.float32) / 255.0


def tensor(img):
    return ((img - MEAN) / STD).transpose(2, 0, 1)[None].astype(np.float32)


def check(fp32, int8, images):
    import onnxruntime as ort

    a = ort.InferenceSession(fp32, providers=["CPUExecutionProvider"])
    b = ort.InferenceSession(int8, providers=["CPUExecutionProvider"])
    inp = a.get_inputs()[0]
    lines = [f"input {inp.name} {inp.shape} {inp.type}; output {a.get_outputs()[0].name} {a.get_outputs()[0].shape}"]
    for name, img in [("synthetic", synthetic_input())] + [(p, image_input(p)) for p in images]:
        x = tensor(img)
        t0 = time.time()
        la = a.run(None, {inp.name: x})[0]
        t1 = time.time()
        lb = b.run(None, {inp.name: x})[0]
        t2 = time.time()
        if la.shape != (1, 18, 128, 128):
            raise SystemExit(f"unexpected output shape {la.shape}")
        agree = float((la.argmax(1) == lb.argmax(1)).mean())
        classes = np.bincount(la.argmax(1).ravel(), minlength=18)
        top = ", ".join(f"{c}:{n}" for c, n in sorted(enumerate(classes), key=lambda t: -t[1])[:5] if n)
        lines.append(f"{os.path.basename(name)}: 8-bit agrees with fp32 on {100 * agree:.2f} % of cells; "
                     f"fp32 {1000 * (t1 - t0):.0f} ms, 8-bit {1000 * (t2 - t1):.0f} ms on this machine's CPU; "
                     f"fp32 classes {top}")
    for line in lines:
        print(line)
    return lines


def main(argv):
    p = argparse.ArgumentParser()
    p.add_argument("--cache", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--check-image", action="append", default=[])
    a = p.parse_args(argv)
    os.makedirs(a.cache, exist_ok=True)
    os.makedirs(a.out, exist_ok=True)

    mp = fetch(MEDIAPIPE, a.cache)
    sf = fetch(SEGFORMER, a.cache)
    int8 = os.path.join(a.cache, OUT_SEGFORMER)
    quantise(sf, int8)
    report = check(sf, int8, a.check_image)

    shutil.copyfile(mp, os.path.join(a.out, OUT_MEDIAPIPE))
    shutil.copyfile(int8, os.path.join(a.out, OUT_SEGFORMER))
    with open(os.path.join(a.out, "MODELS.txt"), "w", newline="\n") as f:
        f.write("Written by tool/models_in_docker.sh. Not committed (D12).\n\n")
        f.write(f"{OUT_MEDIAPIPE}\n  from {MEDIAPIPE['url']}\n  sha256 {sha256(mp)}\n  licence Apache 2.0\n\n")
        f.write(f"{OUT_SEGFORMER}\n  from {SEGFORMER['url']}\n  source sha256 {SEGFORMER['sha256']}\n"
                f"  sha256 {sha256(int8)}\n  8-bit dynamic quantisation of MatMul and Conv weights (uint8)\n"
                f"  licence: training code MIT; weights card 'license: other', no terms stated; ATR dataset research terms\n\n")
        f.write("Quantisation check:\n" + "".join(f"  {line}\n" for line in report))
    print(f"wrote {OUT_MEDIAPIPE}, {OUT_SEGFORMER} and MODELS.txt to {a.out}")


if __name__ == "__main__":
    main(sys.argv[1:])
