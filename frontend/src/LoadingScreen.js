import React, { useEffect, useState, useRef } from 'react';
import {
  Box,
  Card,
  CardContent,
  Button,
  createTheme,
  ThemeProvider,
} from '@mui/material';

import vectorImg from './art/pbrain.png';

const theme = createTheme({
  palette: {
    primary: { main: '#673ab7' },
    secondary: { main: '#9c27b0' },
  },
});

export default function LoadingScreen({
  payload,
  onBack,
  onProceed,
  setProcessStatus,
  setIsProcessing,
  setProcessedOk
}) {

  const [logs, setLogs] = useState('');
  const bottomRef = useRef(null);
  const [logsReady, setLogsReady] = useState(false);

  useEffect(() => {
    const es = new EventSource('http://localhost:8080/api/logs');
    es.onopen = () => setLogsReady(true);
    es.onmessage = e => setLogs(prev => prev + e.data + '\n');
    es.onerror = () => es.close();
    return () => es.close();
  }, []);

  // auto‑scroll whenever logs change
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [logs]);


  useEffect(() => {
    if (!payload || !logsReady) return;
    setIsProcessing(true);
    setProcessStatus('Processing... Please wait.');

    const doProcess = async () => {
      try {
        const res = await fetch('http://localhost:8080/api/processUploads', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload),
        });
        const text = await res.text();
        if (res.ok) {
          setProcessStatus(text);
          setProcessedOk(true);
        } else {
          setProcessStatus(`Processing failed (${res.status}): ${text}`);
        }
      } catch (err) {
        setProcessStatus('Error connecting to server: ' + err.message);
      } finally {
        setIsProcessing(false);
      }
    };

    doProcess();
  }, [payload, logsReady, setIsProcessing, setProcessStatus, setProcessedOk]);

  return (
    <ThemeProvider theme={theme}>
      <Box
        sx={{
          display: 'flex',
          flexGrow: 1,
          minHeight: '100vh',
          textAlign: 'center',
          bgcolor: 'background.default',
          justifyContent: 'center',
          alignItems: 'center',
          p: 3
        }}
      >
        <Card variant='outlined' sx={{ maxWidth: 600, width: '100%', mb: 3 }}>
          <CardContent>

            <Box
              component="img"
              src={vectorImg}
              alt="Purple Search"
              sx={{ width: 250, mb: 2, mx: 'auto' }}
            />

            {/* live logs*/}
            <Box
              component="pre"
              sx={{
                fontFamily: 'monospace',
                whiteSpace: 'pre-wrap',
                textAlign: 'left',
                overflowY: 'auto',
                height: 180,
                border: 1,
                borderColor: 'grey.400',
                borderRadius: 2,
                p: 1,
              }}
            >
              {logs}
              <div ref={bottomRef} />
            </Box>

            <Box sx={{ display: 'flex', justifyContent: 'space-between', mt: 2 }}>
              <Button
                variant="outlined"
                onClick={onBack}
              >
                Back
              </Button>
              <Button
                variant="contained"
                onClick={onProceed}
                disabled={!payload}
              >
                Proceed
              </Button>
            </Box>
          </CardContent>
        </Card>
      </Box>
    </ThemeProvider>
  );
}
