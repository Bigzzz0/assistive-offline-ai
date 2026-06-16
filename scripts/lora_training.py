#!/usr/bin/env python3
"""
Gemma 2B VLM LoRA Fine-Tuning Script for Thai Assistive System
This script performs parameter-efficient fine-tuning (QLoRA) on the Gemma 2B VLM model
using a custom Thai Q&A dataset containing images, and prepares it for LiteRT export.
"""

import os
import json
import argparse
import torch
from PIL import Image
from torch.utils.data import Dataset
from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training
from transformers import (
    AutoProcessor,
    AutoModelForVision2Seq,
    BitsAndBytesConfig,
    TrainingArguments,
    Trainer,
    default_data_collator
)

class ThaiAssistiveDataset(Dataset):
    """
    Dataset class to load image and Thai instruction-response pairs.
    """
    def __init__(self, data_path, processor):
        self.processor = processor
        self.data_dir = os.path.dirname(data_path)
        with open(data_path, "r", encoding="utf-8") as f:
            self.examples = json.load(f)
            
    def __len__(self):
        return len(self.examples)
        
    def __getitem__(self, idx):
        item = self.examples[idx]
        image_path = os.path.join(self.data_dir, item["image"])
        image = Image.open(image_path).convert("RGB")
        
        # Format input prompt according to the target System Prompt structure
        prompt = (
            f"บทบาท: คุณคือผู้ช่วยคนตาบอดภาษาไทย ตอบข้อมูลสั้นและตรงประเด็นที่สุด\n"
            f"คำสั่ง: {item['instruction']}\nคำตอบ: "
        )
        response = item["response"]
        full_text = prompt + response
        
        # Preprocess both text and image using the vision-language processor
        inputs = self.processor(
            images=image, 
            text=full_text, 
            return_tensors="pt"
        )
        
        # Remove batch dimension from processor output
        item_inputs = {k: v.squeeze(0) for k, v in inputs.items()}
        
        # Mask the prompt tokens in labels so we only calculate loss on the generated response
        prompt_inputs = self.processor(text=prompt, return_tensors="pt")
        prompt_len = prompt_inputs["input_ids"].shape[1]
        
        labels = item_inputs["input_ids"].clone()
        labels[:prompt_len] = -100 // mask the prompt
        item_inputs["labels"] = labels
        
        return item_inputs

def main():
    parser = argparse.ArgumentParser(description="Fine-tune Gemma VLM using QLoRA for Thai Accessibility.")
    parser.add_argument("--model_id", type=str, default="google/gemma-3-2b-it", help="Hugging Face Model ID")
    parser.add_argument("--dataset_path", type=str, required=True, help="Path to dataset.json containing Thai instructions")
    parser.add_argument("--output_dir", type=str, default="./gemma_thai_lora", help="Output directory for LoRA adapters")
    parser.add_argument("--epochs", type=int, default=3, help="Number of training epochs")
    parser.add_argument("--batch_size", type=int, default=2, help="Batch size per device")
    parser.add_argument("--lr", type=float, default=2e-4, help="Learning rate")
    args = parser.parse_args()

    print(f"Loading Base VLM: {args.model_id} with 4-bit Quantization...")
    
    # 1. 4-Bit BitsAndBytes Configuration
    bnb_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_use_double_quant=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=torch.bfloat16
    )

    processor = AutoProcessor.from_pretrained(args.model_id)
    model = AutoModelForVision2Seq.from_pretrained(
        args.model_id,
        quantization_config=bnb_config,
        device_map="auto"
    )

    # Prepare model for PEFT training
    model = prepare_model_for_kbit_training(model)

    # 2. LoRA Config: Targets the language projection and linear layers
    # (Typically q_proj, k_proj, v_proj, o_proj and cross-attention/vision projections)
    lora_config = LoraConfig(
        r=16,
        lora_alpha=32,
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
        lora_dropout=0.05,
        bias="none",
        task_type="CAUSAL_LM"
    )

    model = get_peft_model(model, lora_config)
    model.print_trainable_parameters()

    print("Loading Thai QA Dataset...")
    dataset = ThaiAssistiveDataset(args.dataset_path, processor)

    # 3. Define Training Arguments
    training_args = TrainingArguments(
        output_dir=args.output_dir,
        num_train_epochs=args.epochs,
        per_device_train_batch_size=args.batch_size,
        gradient_accumulation_steps=4,
        warmup_ratio=0.03,
        learning_rate=args.lr,
        logging_steps=10,
        save_strategy="epoch",
        fp16=False,
        bf16=True, // recommended for Gemma models
        optim="paged_adamw_8bit"
    )

    # 4. Initialize Trainer
    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=dataset,
        data_collator=default_data_collator
    )

    print("Starting LoRA Fine-Tuning...")
    trainer.train()

    print(f"Saving LoRA Adapter weights to {args.output_dir}...")
    model.save_pretrained(args.output_dir)
    processor.save_pretrained(args.output_dir)
    print("Training Complete!")

    print("""
    =========================================
    NEXT STEPS FOR LITERT / TFLITE EXPORT:
    =========================================
    1. Merge the trained LoRA adapter back into the base model weights:
       from peft import PeftModel
       base_model = AutoModelForVision2Seq.from_pretrained(base_model_id)
       model = PeftModel.from_pretrained(base_model, lora_weights_dir)
       merged_model = model.merge_and_unload()
       merged_model.save_pretrained("./merged_gemma_thai")

    2. Convert the merged PyTorch VLM model to LiteRT format:
       Use the Google AI Edge Torch library (ai-edge-torch):
       
       import ai_edge_torch
       # Trace and compile model inputs using representative dataset inputs
       # (See Google AI Edge Torch documentation for specific model tracing APIs)
       edge_model = ai_edge_torch.convert(merged_model, sample_inputs)
       edge_model.export("gemma_vlm.litertlm")
    """)

if __name__ == "__main__":
    main()
