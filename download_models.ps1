$tempDir = Join-Path $PSScriptRoot "temp_models"
if (-not (Test-Path $tempDir)) {
    New-Item -ItemType Directory -Path $tempDir
}

$files = @(
    @{
        Url  = "https://huggingface.co/yfyeung/icefall-asr-gigaspeech2-th-zipformer-2024-06-20/resolve/main/exp/encoder-epoch-12-avg-5.int8.onnx"
        Dest = Join-Path $tempDir "encoder.onnx"
    },
    @{
        Url  = "https://huggingface.co/yfyeung/icefall-asr-gigaspeech2-th-zipformer-2024-06-20/resolve/main/exp/decoder-epoch-12-avg-5.int8.onnx"
        Dest = Join-Path $tempDir "decoder.onnx"
    },
    @{
        Url  = "https://huggingface.co/yfyeung/icefall-asr-gigaspeech2-th-zipformer-2024-06-20/resolve/main/exp/joiner-epoch-12-avg-5.int8.onnx"
        Dest = Join-Path $tempDir "joiner.onnx"
    },
    @{
        Url  = "https://huggingface.co/yfyeung/icefall-asr-gigaspeech2-th-zipformer-2024-06-20/resolve/main/data/lang_bpe_2000/tokens.txt"
        Dest = Join-Path $tempDir "tokens.txt"
    },
    @{
        Url  = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        Dest = Join-Path $tempDir "gemma-4-E2B-it.litertlm"
    }
)

Write-Host "Starting downloads using curl.exe..."
foreach ($file in $files) {
    $url = $file.Url
    $dest = $file.Dest
    $name = Split-Path $dest -Leaf
    
    Write-Host "Downloading $name..."
    if (Test-Path $dest) {
        # Try to resume or skip if complete
        curl.exe -L -C - -o "$dest" "$url"
    }
    else {
        curl.exe -L -o "$dest" "$url"
    }
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Successfully downloaded $name"
    }
    else {
        Write-Warning "Failed to download $name (Exit Code: $LASTEXITCODE)"
    }
}
