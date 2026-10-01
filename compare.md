# Recognition models compared

1237 side-on photos of 136 Holstein cows behind rails (CC0, <https://doi.org/10.34894/O1ZBSA>). The cow finder boxed the cow in 1192. Top-1: the most alike other photo is the same cow.

| Model | Parameters | Top-1, cow boxed | Top-1, whole photo | Named right at the 2% threshold (boxed) | Time a photo |
|---|---|---|---|---|---|
| MegaDescriptor-T-224 (Swin-T, 28M) | 27.5M | 33.4% | 7.4% | 2.1% | 87 ms |
| MegaDescriptor-T-224, ImageNet scaling | 27.5M | 32.2% | 7.6% | 2.1% | 87 ms |
| MegaDescriptor-S-224 (Swin-S, 50M) | 48.8M | 42.4% | 11.2% | 1.5% | 160 ms |
| MegaDescriptor-B-224 (Swin-B, 88M) | 86.7M | 47.4% | 17.9% | 1.1% | 267 ms |
| MegaDescriptor-L-384 (Swin-L, 228M) | 195.2M | 50.4% | 36.9% | 2.3% | 1593 ms |
| MegaDescriptor-T-CNN-288 (EfficientNet) | | not available | | | |
| DINOv2 ViT-S/14 (22M, Apache 2.0) | 21.6M | 40.6% | 17.1% | 3.5% | 101 ms |
| DINOv2 ViT-B/14 (87M, Apache 2.0) | 85.7M | 36.9% | 26.1% | 7.0% | 336 ms |
| MobileNetV3 ImageNet features (what the web version uses) | 4.2M | 34.6% | 10.2% | 1.1% | 5 ms |

Times are PyTorch on GitHub's machine, for comparing the models with each other, not what a phone takes.
