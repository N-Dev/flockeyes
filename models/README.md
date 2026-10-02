# Models

| File | What it is | Licence |
|---|---|---|
| `cows-tiny.onnx`, `cows-nano.onnx` | The cow finder: `yolox_tiny.onnx` and `yolox_nano.onnx`, unchanged, from [YOLOX 0.1.1rc0](https://github.com/Megvii-BaseDetection/YOLOX/releases/tag/0.1.1rc0) (Copyright (c) 2021-2022 Megvii Inc.). Trained on COCO, which has a "cow" class. | Apache 2.0 (`LICENSE-YOLOX.txt`) |
| `cow-reid.onnx`, `cow-reid.json` | The recognition model: [MegaDescriptor-T-224](https://huggingface.co/BVRA/MegaDescriptor-T-224) (Čermák, Picek, Adam, Papafitsoros: *WildlifeDatasets: An open-source toolkit for animal re-identification*, WACV 2024), exported to ONNX with 8-bit weights by `tools/prepare_reid.py`. The `.json` says how to feed it and holds the bars a look must clear to be named. | **CC BY-NC 4.0: non-commercial use only** |
| `cow-tuning.bin` | The tuning that goes with the recognition model: the directions its descriptions of ONE cow vary along (how it stands, how the box was cut), which the app scales down before comparing two looks. Made by `tools/make_tuning.py` from the model's descriptions of the barn photos (CC0) and of looks from field videos (`tools/data`). | As the model it was made with |

`cow-reid.onnx`, `cow-tuning.bin` and `cow-reid.json` are made by the *Models* workflow
(`.github/workflows/models.yml`), which commits them here; what they achieve is in
[docs/recognition.md](../docs/recognition.md). Because of the recognition model's licence, an app that
includes it can't be sold.
