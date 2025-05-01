import React from 'react';
import {
  Box, Typography, FormControl, InputLabel, Select, MenuItem,
  Divider, TextField, Button, Alert, Tooltip
} from '@mui/material';
import InfoIcon from '@mui/icons-material/Info';

import {
  LLM_PROVIDERS,
  DEFAULT_MODEL_CONFIG,
} from '../modelConfig';

export default function ModelConfigPanel({
  config = DEFAULT_MODEL_CONFIG, // this holds your config pre-fills if you choose to have them
  onConfigChange,
  onSaveConfig,
  isSaving,
  saveStatus,
  actionButtonText = "Save Config" // button label
}) {

  // Helper functions to update specific parts of config
  const updateEmbeddings = (key, value) => {
    onConfigChange({
      ...config,
      embeddings: {
        ...config.embeddings,
        [key]: value
      }
    });
  };

  const updateLLM = (key, value) => {
    onConfigChange({
      ...config,
      llm: {
        ...config.llm,
        [key]: value
      }
    });
  };

  return (
    <Box>

      {/* LLM Section */}
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 3 }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 'bold' }}>
          LLM
        </Typography>

        <Tooltip title="View LLM models documentation">
          <Button
            size="small"
            variant="outlined"
            sx={{ ml: 2, minWidth: 'auto', height: 28 }}
            href="https://platform.openai.com/docs/models"
            target="_blank"
            rel="noopener noreferrer"
          >
            <InfoIcon fontSize="small" sx={{ mr: 0.5 }} />
            Models
          </Button>
        </Tooltip>
      </Box>

      <FormControl size="small" sx={{ minWidth: 140, mr: 2, mb: 1 }}>
        <InputLabel>Provider</InputLabel>
        <Select
          label="Provider"
          value={config.llm.provider}
          onChange={(e) => {
            const nextProvider = e.target.value;
            updateLLM('provider', nextProvider);
          }}
        >
          {LLM_PROVIDERS.map((provider) => (
            <MenuItem key={provider} value={provider}>
              {provider}
            </MenuItem>
          ))}
        </Select>
      </FormControl>

      <TextField
        label='Model Name'
        variant='outlined'
        size='small'
        fullWidth
        sx={{ mt: 1, mb: 2 }}
        value={config.llm.model ?? ''}
        onChange={(e) => updateLLM('model', e.target.value)}
      />

      <TextField
        label="API Key"
        type="password"
        variant="outlined"
        size="small"
        fullWidth
        sx={{ mt: 1, mb: 2 }}
        value={config.llm.apiKey ?? ''}
        onChange={(e) => updateLLM('apiKey', e.target.value)}
      />

      <Typography variant="caption" color="text.secondary" sx={{ mb: 0.5 }}>
        This token limit will be used to truncate LLM input and avoid errors caused by over sized inputs.
      </Typography>
      <TextField
        label="Token Limit per Request"
        variant="outlined"
        size="small"
        fullWidth
        sx={{ mt: 1, mb: 2 }}
        value={config.llm.tpr ?? ''}
        onChange={(e) => updateLLM('tpr', e.target.value)}
      />

      <Divider sx={{ my: 2 }} />

      {/* Embeddings Section */}
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 3 }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 'bold' }}>
          Embeddings
        </Typography>

        <Tooltip title="View embedding models documentation">
          <Button
            size="small"
            variant="outlined"
            sx={{ ml: 2, minWidth: 'auto', height: 28 }}
            href="https://huggingface.co/Salesforce/SFR-Embedding-Code-400M_R"
            target="_blank"
            rel="noopener noreferrer"
          >
            <InfoIcon fontSize="small" sx={{ mr: 0.5 }} />
            Models
          </Button>
        </Tooltip>
      </Box>

      <TextField
        label='Hugging Face Endpoint URL'
        variant='outlined'
        size='small'
        fullWidth
        sx={{ mt: 1, mb: 2 }}
        value={config.embeddings.url ?? ''}
        onChange={(e) => updateEmbeddings('url', e.target.value)}
      />

      <TextField
        label='Hugging Face Token'
        variant='outlined'
        size='small'
        fullWidth
        type="password"
        sx={{ mt: 1, mb: 2 }}
        value={config.embeddings.token ?? ''}
        onChange={(e) => updateEmbeddings('token', e.target.value)}
      />

      <TextField
        label="Output Vector Dimension"
        variant="outlined"
        size="small"
        fullWidth
        sx={{ mt: 1, mb: 2 }}
        value={config.embeddings.dim ?? ''}
        onChange={(e) => updateEmbeddings('dim', e.target.value)}
      />

      <Typography variant="caption" color="text.secondary" sx={{ mb: 0.5 }}>
        The program will try to embedd full methods but sometimes they are too large for the model. Set a fallback token amount to truncate code snippets if ever you encounter a snippet(entire method) thats too large for your model.
      </Typography>

      <TextField
        label="Fallback token limit per embedding"
        variant="outlined"
        size="small"
        fullWidth
        sx={{ mt: 1, mb: 2 }}
        value={config.embeddings.tpe ?? ''}
        onChange={(e) => updateEmbeddings('tpe', e.target.value)}
      />

      <Divider sx={{ my: 2 }} />

      {/* Action Button */}
      <Box sx={{ mt: 2 }}>
        <Button
          variant="contained"
          onClick={onSaveConfig}
          disabled={isSaving}
        >
          {isSaving ? 'Saving...' : actionButtonText}
        </Button>

        {saveStatus && (
          <Alert
            severity={saveStatus.toLowerCase().includes('fail') ? 'error' : 'success'}
            sx={{ mt: 2 }}
          >
            {saveStatus}
          </Alert>
        )}
      </Box>
    </Box>
  );
}