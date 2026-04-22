#!/usr/bin/env node
"use strict";

const { program } = require("commander");
const readline = require("readline");

const DEFAULT_URL = "http://192.168.1.100:11434";

// ── Helpers ──────────────────────────────────────────────────────────────────

async function apiGet(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${await res.text()}`);
  return res.json();
}

async function streamGenerate(baseUrl, body) {
  const res = await fetch(`${baseUrl}/api/generate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

  if (!res.ok) {
    const err = await res.text();
    throw new Error(`HTTP ${res.status}: ${err}`);
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split("\n");
    buffer = lines.pop(); // keep incomplete line

    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed) continue;
      try {
        const obj = JSON.parse(trimmed);
        if (obj.token) process.stdout.write(obj.token);
        if (obj.done) process.stdout.write("\n");
        if (obj.error) throw new Error(obj.error);
      } catch (e) {
        if (e.message !== "generation failed") throw e;
      }
    }
  }
}

// ── Commands ──────────────────────────────────────────────────────────────────

program
  .name("pocketnode")
  .description("Pocket Node Edge API client")
  .version("1.0.0");

program
  .command("ping")
  .description("Check if the Edge API is running on your Android device")
  .option("-u, --url <url>", "Device Edge API URL", DEFAULT_URL)
  .action(async (opts) => {
    try {
      const status = await apiGet(opts.url);
      console.log("Status:", status.status);
      if (status.model) console.log("Model: ", status.model);
      if (status.backend) console.log("Backend:", status.backend);
    } catch (e) {
      console.error("Error:", e.message);
      process.exit(1);
    }
  });

program
  .command("generate")
  .description("Generate a one-shot response from the model")
  .requiredOption("-p, --prompt <text>", "Prompt to send")
  .option("-u, --url <url>", "Device Edge API URL", DEFAULT_URL)
  .option("-t, --temperature <num>", "Temperature (0.1–2.0)", parseFloat, 0.7)
  .option("-k, --max-tokens <num>", "Max tokens to generate", parseInt, 512)
  .action(async (opts) => {
    try {
      await streamGenerate(opts.url, {
        prompt: opts.prompt,
        temperature: opts.temperature,
        max_tokens: opts.maxTokens,
      });
    } catch (e) {
      console.error("\nError:", e.message);
      process.exit(1);
    }
  });

program
  .command("chat")
  .description("Interactive chat REPL (Ctrl+C to exit)")
  .option("-u, --url <url>", "Device Edge API URL", DEFAULT_URL)
  .option("-t, --temperature <num>", "Temperature", parseFloat, 0.7)
  .option("-k, --max-tokens <num>", "Max tokens per reply", parseInt, 512)
  .action(async (opts) => {
    // Verify connection first
    try {
      const status = await apiGet(opts.url);
      if (status.status !== "ok") {
        console.error("No model loaded on device. Load a model first.");
        process.exit(1);
      }
      console.log(`Connected to ${status.model} (${status.backend ?? "CPU"})`);
      console.log('Type your message and press Enter. Ctrl+C to quit.\n');
    } catch (e) {
      console.error("Cannot reach Edge API:", e.message);
      console.error(`Is the device on the same network? URL: ${opts.url}`);
      process.exit(1);
    }

    const rl = readline.createInterface({
      input: process.stdin,
      output: process.stdout,
      terminal: process.stdin.isTTY,
    });

    const ask = () =>
      new Promise((resolve) => {
        if (process.stdin.isTTY) process.stdout.write("You: ");
        rl.once("line", resolve);
      });

    while (true) {
      let userInput;
      try {
        userInput = await ask();
      } catch {
        break;
      }

      if (!userInput?.trim()) continue;

      if (process.stdin.isTTY) process.stdout.write("AI: ");
      try {
        await streamGenerate(opts.url, {
          prompt: userInput.trim(),
          temperature: opts.temperature,
          max_tokens: opts.maxTokens,
        });
      } catch (e) {
        console.error("\nError:", e.message);
      }

      if (process.stdin.isTTY) console.log();
    }

    rl.close();
  });

program.parse();
