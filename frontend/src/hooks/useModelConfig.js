import { useState, useEffect } from 'react';
import {
  DEFAULT_MODEL_CONFIG,
} from '../modelConfig';

export default function useModelConfig() {
  // Local state for the entire model config
  const [config, setConfig] = useState(DEFAULT_MODEL_CONFIG);
  // For indicating when the hook is currently saving to the server
  const [isSaving, setIsSaving] = useState(false);
  const [saveStatus, setSaveStatus] = useState('');

  // On mount, try to fetch an existing config from the server
  useEffect(() => {
    fetchConfigFromServer();
  }, []);

  async function fetchConfigFromServer() {
    try {
      const response = await fetch('http://localhost:8080/api/getInferenceConfig');
      if (response.status === 404) {
        return;
      }
      if (!response.ok) {
        throw new Error(`Failed to fetch config. Server returned status ${response.status}.`);
      }

      const serverConfig = await response.json();
      const nextConfig = {
        embeddings: {
          url: serverConfig.embeddings?.url,
          token: serverConfig.embeddings?.token,
          dim: serverConfig.embeddings?.dim,
          tpe: serverConfig.embeddings?.tpe,
        },
        llm: {
          provider: serverConfig.llm?.provider,
          model: serverConfig.llm?.model,
          apiKey: serverConfig.llm?.apiKey,
          tpr: serverConfig.llm?.tpr,
        },
      };

      setConfig(nextConfig);
    } catch (error) {
      console.error('Error fetching model config:', error);
    }
  }

  async function saveConfigToServer() {
    setIsSaving(true);
    setSaveStatus('');

    try {
      const payload = {
        embeddings: {
          url: config.embeddings.url,
          token: config.embeddings.token,
          dim: config.embeddings.dim,
          tpe: config.embeddings.tpe
        },
        llm: {
          provider: config.llm.provider,
          model: config.llm.model,
          apiKey: config.llm.apiKey,
          tpr: config.llm.tpr
        },
      };

      const response = await fetch('http://localhost:8080/api/setInferenceConfig', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });

      if (!response.ok) {
        const errText = await response.text();
        throw new Error(`Failed to save config: ${errText}`);
      }

      setSaveStatus('Config saved successfully!');
      return true;
    } catch (error) {
      setSaveStatus(`Failed to save: ${error.message}`);
      return false;
    } finally {
      setIsSaving(false);
    }
  }

  return {
    config,
    setConfig,
    isSaving,
    saveStatus,
    saveConfigToServer,
  };
}
