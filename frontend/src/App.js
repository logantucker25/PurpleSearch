import React, { useState, useEffect } from 'react';
import {
  Box,
  Card,
  CardContent,
  Typography,
  Button,
  TextField,
  Paper,
  Alert,
  IconButton,
  Stepper,
  Step,
  StepLabel,
  StepContent,
  createTheme,
  ThemeProvider,
} from '@mui/material';
import RefreshIcon from '@mui/icons-material/Refresh';

import ModelConfigPanel from './components/ModelConfigPanel';
import SecondScreen from './SecondScreen';
import LoadingScreen from './LoadingScreen'
import useModelConfig from './hooks/useModelConfig';


const theme = createTheme({
  palette: {
    primary: { main: '#673ab7' },
    secondary: { main: '#9c27b0' },
  },
});

function App() {
  const { ipcRenderer } = window.require('electron');

  // shared model config hook
  const {
    config,
    setConfig,
    isSaving: configSaving,
    saveStatus: configSaveStatus,
    saveConfigToServer,
  } = useModelConfig();

  // Show wizard or loading graph screen or second screen
  const [showWizard, setShowWizard] = useState(true);
  const [showLoading, setShowLoading] = useState(false);

  // Connection status
  const [serverConnected, setServerConnected] = useState(false);
  const [neo4jConnected, setNeo4jConnected] = useState(null);

  // File/folder upload
  const [files, setFiles] = useState([]);
  const [isUploading, setIsUploading] = useState(false);
  const [uploadSuccess, setUploadSuccess] = useState(false);
  const [uploadError, setUploadError] = useState(null);

  // Processing
  const [isProcessing, setIsProcessing] = useState(false);
  const [processStatus, setProcessStatus] = useState(null);
  const [processedOk, setProcessedOk] = useState(false);
  const [processPayload, setProcessPayload] = useState(null);

  // Neo4j options
  const [neo4jUrl, setNeo4jUrl] = useState('bolt://localhost:7687');
  const [neo4jUsername, setNeo4jUsername] = useState('neo4j');
  const [neo4jPassword, setNeo4jPassword] = useState('');

  // Stepper
  const [activeStep, setActiveStep] = useState(0);

  // Track if the graph is empty
  const [graphEmpty, setGraphEmpty] = useState(null);

  // Track if config has been saved this session
  const [configSaved, setConfigSaved] = useState(false);

  // Track if config was saved at least once
  const [configEverSaved, setConfigEverSaved] = useState(false);

  // ─────────────────────────────────────────────────────────────────────────────
  // Server connection check
  // ─────────────────────────────────────────────────────────────────────────────
  useEffect(() => {
    setProcessStatus('Connecting to server...');

    const checkServerConnection = async () => {
      try {
        const response = await fetch(
          'http://localhost:8080/api/uploadDirectory?ping=1',
          { method: 'HEAD' }
        );
        const isConnected = response.ok;

        if (isConnected !== serverConnected) {
          setServerConnected(isConnected);
          if (isConnected) {
            setProcessStatus('Server connected successfully!');
          } else if (activeStep > 0) {
            setActiveStep(0);
            setProcessStatus(
              'Server connection lost. Please wait for reconnection.'
            );
          } else {
            setProcessStatus('Cannot connect to server. Is it running?');
          }
        }
      } catch (err) {
        console.error('Server connection check failed:', err);
        if (serverConnected) {
          setServerConnected(false);
          if (activeStep > 0) setActiveStep(0);
          setProcessStatus(
            'Server connection lost. Please wait for reconnection.'
          );
        }
      }
    };

    checkServerConnection();
    const interval = setInterval(checkServerConnection, 5000);
    return () => clearInterval(interval);
  }, [activeStep, serverConnected]);

  // ─────────────────────────────────────────────────────────────────────────────
  // Graph / Neo4j operations
  // ─────────────────────────────────────────────────────────────────────────────
  const handleResetGraph = async () => {
    try {
      const response = await fetch('http://localhost:8080/api/resetGraph', {
        method: 'POST',
      });
      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`Reset failed: ${errorText}`);
      }
      await checkGraphEmpty();
    } catch (err) {
      console.error('Reset graph error:', err);
    }
  };

  const handleConnectNeo4j = async () => {
    try {
      setNeo4jConnected(null);
      setProcessStatus('Connecting to Neo4j...');

      const response = await fetch('http://localhost:8080/api/connectToNeo4j', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          url: neo4jUrl,
          username: neo4jUsername,
          password: neo4jPassword,
        }),
      });

      if (!response.ok) {
        const errText = await response.text();
        throw new Error(`Failed to connect: ${errText}`);
      }

      const data = await response.json();
      const isConnected = data.connected === true;
      setNeo4jConnected(isConnected);

      await checkGraphEmpty();

      if (isConnected) {
        setProcessStatus('Neo4j connected successfully. Ready to upload files.');
      } else {
        setProcessStatus('Failed to connect to Neo4j. Please check credentials.');
      }
    } catch (err) {
      console.error(err);
      setNeo4jConnected(false);
      setProcessStatus(`Neo4j connection error: ${err.message}`);
    }
  };

  const checkGraphEmpty = async () => {
    try {
      const response = await fetch('http://localhost:8080/api/isGraphEmpty');
      if (!response.ok) {
        throw new Error('Failed to check if graph is empty');
      }
      const data = await response.json();
      setGraphEmpty(data.isEmpty);
    } catch (err) {
      console.error(err);
      setGraphEmpty(null);
    }
  };

  // ─────────────────────────────────────────────────────────────────────────────
  // File/folder upload
  // ─────────────────────────────────────────────────────────────────────────────
  const handleBrowse = async () => {
    try {
      const selectedPaths = await ipcRenderer.invoke('open-file-or-folder-dialog');
      if (selectedPaths && selectedPaths.length > 0) {
        setFiles(selectedPaths);
        setUploadSuccess(false);
        setUploadError(null);
      }
    } catch (err) {
      console.error('Failed to open dialog:', err);
      setUploadError('Failed to open file dialog: ' + err.message);
    }
  };

  const handleUpload = async () => {
    if (!serverConnected) {
      setUploadError('Cannot connect to server. Is it running?');
      return;
    }
    if (!files.length) {
      setUploadError('No files/folders to upload');
      return;
    }

    setIsUploading(true);
    setUploadError(null);

    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 10000);

      const response = await fetch('http://localhost:8080/api/uploadDirectory', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ projectRoot: files[0] }),
        signal: controller.signal,
      });

      clearTimeout(timeoutId);

      if (response.ok) {
        const responseText = await response.text();
        console.log('Upload response:', responseText);
        setUploadSuccess(true);
      } else {
        const errorText = await response.text();
        console.error('Upload failed:', response.status, errorText);
        setUploadError(`Upload failed (${response.status}): ${errorText}`);
      }
    } catch (err) {
      console.error('Upload error:', err);
      setUploadError('Error connecting to server: ' + err.message);
    } finally {
      setIsUploading(false);
    }
  };

  // ─────────────────────────────────────────────────────────────────────────────
  // saving config
  // ─────────────────────────────────────────────────────────────────────────────
  const handleConfigChange = (newConfig) => {
    setConfig(newConfig);
    setConfigSaved(false);
  };

  const handleSaveConfig = async () => {
    try {
      await saveConfigToServer();
      setConfigSaved(true);
      setConfigEverSaved(true);
    } catch (err) {
      console.error('Failed to save config:', err);
      // optional: set some error message state
    }
  };

  const handleProcess = () => {
    if (!serverConnected) {
      setProcessStatus('Cannot connect to server. Is it running?');
      return;
    }
    if (!configSaved) {
      setProcessStatus('Please save the config first.');
      return;
    }

    setProcessPayload({
      projectRoot: files[0],
      neo4j: { url: neo4jUrl, username: neo4jUsername, password: neo4jPassword },
    });

    setShowLoading(true);
  };

  // ─────────────────────────────────────────────────────────────────────────────
  // Wizard steps
  // ─────────────────────────────────────────────────────────────────────────────
  const steps = [
    {

      label: 'Connect to Neo4j',
      content: (
        <>

          {/* Server Connection Status */}
          <Paper
            variant="outlined"
            sx={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              p: 2,
              mb: 2,
              borderColor: serverConnected ? 'success.main' : 'error.main',
              borderWidth: 2,
            }}
          >
            <Box sx={{ display: 'flex', alignItems: 'center' }}>
              <Box
                sx={{
                  width: 14,
                  height: 14,
                  borderRadius: '50%',
                  mr: 1.5,
                  bgcolor: serverConnected ? 'success.main' : 'error.main',
                }}
              />
              <Typography variant='body1' sx={{ fontWeight: 600 }}>
                Server: {serverConnected ? 'Connected' : 'Connecting...'}
              </Typography>
            </Box>
            <Alert
              severity={serverConnected ? 'success' : 'info'}
              sx={{ ml: 2, flex: 1 }}
            >
              {processStatus}
            </Alert>
            {!serverConnected && (
              <IconButton
                color='primary'
                size='small'
                onClick={() => window.location.reload()}
                sx={{ ml: 1 }}
              >
                <RefreshIcon fontSize='small' />
              </IconButton>
            )}
          </Paper>

          <TextField
            label='URL'
            variant='outlined'
            size='small'
            fullWidth
            sx={{ mb: 2 }}
            value={neo4jUrl}
            onChange={(e) => setNeo4jUrl(e.target.value)}
          />
          <TextField
            label='Username'
            variant='outlined'
            size='small'
            fullWidth
            sx={{ mb: 2 }}
            value={neo4jUsername}
            onChange={(e) => setNeo4jUsername(e.target.value)}
          />
          <TextField
            label='Password'
            variant='outlined'
            size='small'
            type='password'
            fullWidth
            sx={{ mb: 3 }}
            value={neo4jPassword}
            onChange={(e) => setNeo4jPassword(e.target.value)}
          />

          <Button
            variant='contained'
            onClick={handleConnectNeo4j}
            disabled={!serverConnected}
          >
            Connect to Neo4j
          </Button>

          {neo4jConnected ? (
            <Box sx={{ mt: 2 }}>
              <Alert severity='success'>Connected to Neo4j successfully!</Alert>
              {graphEmpty === false && (
                <>
                  <Alert severity='success' sx={{ mt: 1 }}>
                    This graph already contains data!
                  </Alert>
                  <Box sx={{ mt: 1, display: 'flex', gap: 1 }}>
                    <Button
                      variant='contained'
                      onClick={() => setActiveStep(2)}
                    >
                      Skip to model setup
                    </Button>
                    <Button
                      variant='outlined'
                      color='error'
                      onClick={handleResetGraph}
                    >
                      Delete Graph Contents
                    </Button>
                  </Box>
                </>
              )}
              <Box sx={{ mt: 2 }}>
                <Button variant='outlined' onClick={() => setActiveStep(1)}>
                  Proceed
                </Button>
              </Box>
            </Box>
          ) : (
            neo4jConnected === false && (
              <Alert severity="error" sx={{ mt: 2 }}>
                Failed to connect to Neo4j
              </Alert>
            )
          )}
        </>
      ),
    },
    {
      label: 'Upload Directory',
      content: (
        <>

          {/* Will confirm item at given adress is valid type */}
          <Typography variant='body2' sx={{ mb: 2 }}>
            Select a project to analyze.
          </Typography>

          <Box sx={{ mb: 2 }}>
            <Button variant='contained' onClick={handleBrowse} sx={{ mr: 1 }}>
              Browse files
            </Button>
            <Button
              variant='outlined'
              onClick={handleUpload}
              disabled={isUploading || !files.length}
            >
              {isUploading ? 'Uploading...' : 'Upload to Server'}
            </Button>

            {uploadError && (
              <Alert severity='error' sx={{ mt: 2 }}>
                {uploadError}
              </Alert>
            )}
            {uploadSuccess && (
              <Alert severity='success' sx={{ mt: 2 }}>
                Project uploaded successfully!
              </Alert>
            )}
          </Box>

          <Typography variant='subtitle2' sx={{ fontWeight: 'bold' }}>
            Selected Paths:
          </Typography>
          <Paper variant='outlined' sx={{ minHeight: 60, p: 1, mb: 2 }}>
            {files.length ? (
              files.map((f, idx) => (
                <Typography variant='body2' key={idx}>
                  • {f}
                </Typography>
              ))
            ) : (
              <Typography
                variant='body2'
                fontStyle='italic'
                color='text.secondary'
              >
                No files selected
              </Typography>
            )}
          </Paper>
          <Box sx={{ mt: 2, display: 'flex', gap: 1 }}>
            <Button
              variant="outlined"
              onClick={() => setActiveStep(2)}
              disabled={!uploadSuccess}
            >
              Proceed
            </Button>
            <Button
              variant="outlined"
              onClick={() => setActiveStep(prev => prev - 1)}
            >
              Back
            </Button>
          </Box>
        </>
      ),
    },
    {
      label: 'Configure AI Models',
      content: (
        <>

          {/* Model config panel with separate "Save Config" button */}
          <ModelConfigPanel
            config={config}
            onConfigChange={handleConfigChange}
            isSaving={configSaving}
            saveStatus={configSaveStatus}
            onSaveConfig={handleSaveConfig}
            actionButtonText='Save Config'
          />

          <Box sx={{ mt: 2, display: 'flex', gap: 1 }}>
            <Button
              variant='contained'
              onClick={handleProcess}
              disabled={!uploadSuccess || isProcessing || !configSaved}
            >
              {isProcessing ? 'Processing...' : 'Create Search New Space'}
            </Button>

            <Button
              variant='contained'
              onClick={() => setShowWizard(false)}
              disabled={isProcessing || !configEverSaved || graphEmpty !== false}
            >
              Use Existing Search Space
            </Button>

            <Button
              variant="outlined"
              onClick={() => setActiveStep(prev => prev - 1)}
            >
              Back
            </Button>
          </Box>
        </>
      ),
    },
  ];

  // ─────────────────────────────────────────────────────────────────────────────
  // Conditional rendering: wizard vs second screen vs loading the graph screen
  // ─────────────────────────────────────────────────────────────────────────────
  if (showLoading) {
    return (
      <LoadingScreen
        payload={processPayload}
        onBack={() => setShowLoading(false)}
        onProceed={() => {
          setShowLoading(false);
          setShowWizard(false);
        }}
        setProcessStatus={setProcessStatus}
        setIsProcessing={setIsProcessing}
        setProcessedOk={setProcessedOk}
      />
    );
  }

  if (!showWizard) {
    return (
      <SecondScreen onBackToWizard={() => {
        setActiveStep(0);
        setShowWizard(true);
      }} />
    );
  }
  return (
    <ThemeProvider theme={theme}>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          minHeight: '100vh',
          bgcolor: 'background.default',
          p: 3,
        }}
      >
        <Card
          variant="outlined"
          sx={{
            width: '100%',
            maxWidth: 900,
            mb: 3,
            mx: 'auto',
          }}
        >
          <CardContent>
            <Stepper activeStep={activeStep} orientation="vertical">
              {steps.map((step, index) => (
                <Step key={step.label}>
                  <StepLabel>
                    <Typography variant="subtitle1">{step.label}</Typography>
                  </StepLabel>
                  <StepContent>
                    <Box sx={{ mb: 2 }}>{step.content}</Box>
                  </StepContent>
                </Step>
              ))}
            </Stepper>
          </CardContent>
        </Card>
      </Box>
    </ThemeProvider>
  );
}

export default App;
