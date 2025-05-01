import React, { useState } from 'react';
import {
  Box,
  Paper,
  Typography,
  TextField,
  Button,
  Alert
} from '@mui/material';

export default function QueryPanel() {
  const [userPrompt, setUserPrompt] = useState("");
  const [results, setResults] = useState([]);
  const [error, setError] = useState(null);

  const handleSendQuery = async () => {
    if (!userPrompt.trim()) return;

    setResults([]);
    setError(null);

    try {
      const response = await fetch("http://localhost:8080/api/query", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ prompt: userPrompt }),
      });

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`Query failed: ${response.status} - ${errorText}`);
      }

      const json = await response.json();
      setResults(json.results || []);
    } catch (err) {
      console.error("Query error:", err);
      setError(err.message);
    }
  };

  return (
    <Box sx={{ flex: 1, display: "flex", flexDirection: "column", gap: 2 }}>
      <Typography variant="h6">
        Ask a question about your codebase
      </Typography>

      <Box sx={{ display: "flex", gap: 1 }}>
        <TextField
          variant="outlined"
          size="small"
          fullWidth
          placeholder="Enter your query..."
          value={userPrompt}
          onChange={(e) => setUserPrompt(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") handleSendQuery();
          }}
        />
        <Button variant="contained" onClick={handleSendQuery} disabled={!userPrompt.trim()}>
          Search
        </Button>
      </Box>

      {error && <Alert severity="error">{error}</Alert>}

      <Paper variant="outlined" sx={{ p: 2, mt: 2 }}>
        {results.length === 0 ? (
          <Typography variant="body2" color="text.secondary" align="center">
            No results yet
          </Typography>
        ) : (
          results.map((item, idx) => (
            <Box key={idx} sx={{ mb: 2 }}>
              <Typography variant="subtitle2" sx={{ fontWeight: 'bold' }}>
                Method: {item.methodName}
              </Typography>
              <Typography variant="body2" sx={{ whiteSpace: 'pre-line' }}>
                Similarity: {item.similarity.toFixed(4)}
              </Typography>
              <Typography
                variant="body2"
                sx={{
                  mt: 1,
                  p: 1,
                  backgroundColor: (theme) => theme.palette.grey[100],
                  borderRadius: 1,
                  fontFamily: 'monospace'
                }}
              >
                {item.code}
              </Typography>
            </Box>
          ))
        )}
      </Paper>
    </Box>
  );
}
