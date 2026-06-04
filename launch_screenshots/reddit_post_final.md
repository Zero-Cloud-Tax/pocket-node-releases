I turned an Android phone into a Vulkan-accelerated local LLM node (GGUF + LiteLLM + Tailscale)

Hey everyone — I’ve been working on something that finally reached a stable enough point to share.

I’ve been experimenting with using an Android device as a local inference node inside a self-hosted AI mesh. The goal wasn’t “run a chatbot on Android,” but to make the phone behave like a portable GGUF inference server that plugs into an existing cluster.

### What it currently does

- Loads GGUF models locally on-device
- Uses Vulkan for mobile GPU acceleration
- Exposes an OpenAI-compatible endpoint on the mesh
- Routes through LiteLLM like any other backend
- Joins the cluster through Tailscale
- Supports fallback routing to larger local nodes
- Can run standalone when the rest of the mesh is unavailable

### Architecture

```text
[Android Pocket Node / Z Fold 6]
  GGUF + Vulkan (gpu_layers=89)
  OpenAI-compatible local endpoint
        ↓
[Tailscale Mesh]
        ↓
[Edge Gate on neo-x510uar]
  request pre-flight
  battery / thermal / prompt-size routing
        ↓
[LiteLLM Router on neo-x510uar]
  OpenAI-compatible gateway
  model aliases
  fallback routing
        ↓
[Fallback Nodes]
  sheens-mac-studio — heavier reasoning / judge models
  moolah — RTX box for GPU-heavy workloads
```

### What the screenshots show

1. Z Fold 6 runtime Vulkan proof
2. Tailscale mesh membership with `neo-x510uar`, `sheens-mac-studio`, `moolah`, and `sheens-z-fold6`
3. Small-request routing decision to the Fold 6
4. Oversized-request bypass to `sheens-mac-studio`
5. LiteLLM alias mapping for Fold 6 + fallback

### Important note on the routing screenshots

The `401` in the routing screenshots is expected in that capture. The test request itself was unauthenticated, but the Edge Gate JSONL lines in the same screenshots show the actual routing decisions:

- `allow_fold6` for the small request
- `bypass_oversized` with fallback to `mac-studio-edge-fallback` for the large request

So the routing proof is in the decision log lines, not in the unauthenticated response body.

### Why I think this is interesting

This is not meant to replace a desktop GPU box. The useful part is that a phone can act like a real node in the homelab:

- same API shape
- same router
- same mesh
- same fallback logic

That makes it possible to treat mobile hardware as part of the local inference pool instead of a separate toy demo.

Still rough, but real.
