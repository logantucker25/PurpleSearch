export const LLM_PROVIDERS = ['OpenAI'];

export const DEFAULT_MODEL_CONFIG = {
  embeddings: {
    url: '',
    token: '',
    dim: '',
    tpe: '8000', // tokens per embedding fallback if oversized input error occurs
  },
  llm: {
    provider: 'OpenAI',
    model: 'gpt-4o-mini-2024-07-18',
    apiKey: '',
    tpr: '10000', // tokens per llm prompt
  },
};