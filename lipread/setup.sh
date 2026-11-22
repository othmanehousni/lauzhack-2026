#!/bin/sh
# One-time setup: Python env + MLX lip model (~1.2 GB) + pronunciation dictionary. Runtime is local MLX only.
set -e
cd "$(dirname "$0")"
PY=.venv/bin/python

uv venv -q --allow-existing --python 3.11 .venv
uv pip install -q --python $PY opencv-contrib-python==4.11.0.86 numpy==1.26.4 six==1.17.0 absl-py==2.5.1 \
  attrs==26.1.0 flatbuffers==25.12.19 matplotlib==3.11.2 protobuf==4.25.9 sounddevice==0.5.6 sentencepiece==0.2.3
# mediapipe pinned: 1.x dropped the face-detection API the model code uses. --no-deps skips jax/jaxlib
# (~250 MB), which it lists but never imports for face detection.
uv pip install -q --python $PY --no-deps mediapipe==0.10.21
# MLX: lip model, corrector (mlx-lm), voice output (mlx-audio), word timing during onboarding (mlx-whisper)
uv pip install -q --python $PY mlx==0.32.4 mlx-lm==0.32.2 mlx-audio==0.6.7 mlx-whisper==0.4.3

# Auto-AVSR LRS3 lip-reading model + language model, converted once from PyTorch to MLX. torch and the PyTorch
# files are only needed for that conversion and are removed afterwards.
if [ ! -s mlx_weights/model.safetensors ] || [ ! -s mlx_weights/lm.safetensors ]; then
  B=vsr/benchmarks/LRS3
  for p in models/LRS3_V_WER19.1 language_models/lm_en_subword; do
    mkdir -p "$B/$p"
    for f in model.json model.pth; do  # Hugging Face mirror of the official Google Drive files
      [ -s "$B/$p/$f" ] || curl -fL -o "$B/$p/$f" "https://huggingface.co/Amanvir/$(basename $p)/resolve/main/$f"
    done
  done
  shasum -a 256 -c <<EOF
e740cef369abeabd0ba2c18e37a0661342e1d94d432d6caa77755a11821d8fe3  $B/models/LRS3_V_WER19.1/model.pth
c75aa39020dec98f432c8689b145d3f4cc407d4daa90a0c64202386c34f83c18  $B/language_models/lm_en_subword/model.pth
EOF
  uv pip install -q --python $PY torch==2.15.0
  $PY convert_weights.py
  uv pip uninstall -q --python $PY torch
  rm -f $B/models/LRS3_V_WER19.1/model.pth $B/language_models/lm_en_subword/model.pth
fi

# Pronunciation dictionary (lip shapes for the corrector)
mkdir -p data
[ -s data/cmudict.dict ] || curl -fsSL -o data/cmudict.dict https://raw.githubusercontent.com/cmusphinx/cmudict/master/cmudict.dict
[ -d models/corrector ] || echo "No corrector yet: train it once with 'modal run train_corrector.py' (see README)."
echo "Done. Next: .venv/bin/python lipread.py --onboard, then --calibrate, then lipread.py"
