import React, { useState, useEffect, useRef } from 'react';
import neo4j from 'neo4j-driver';
import {
  Box,
  Typography,
  TextField,
  Button,
  Alert,
  Paper,
  Card,
  CardContent,
  Chip,
  createTheme,
  ThemeProvider,
  CircularProgress,
} from '@mui/material';

import SearchIcon from '@mui/icons-material/Search';
import ChatIcon from '@mui/icons-material/Chat';
import SendIcon from '@mui/icons-material/Send';
import ModelConfigPanel from './components/ModelConfigPanel';
import useModelConfig from './hooks/useModelConfig';
import vectorImg from './art/pbrain.png';
import GraphViewer from './components/GraphViewer';

// Create a LoadingScreen component to reduce duplication
const LoadingScreen = ({ message }) => (
  <Box sx={{ p: 3, textAlign: 'center' }}>
    <Typography variant="h6">{message}</Typography>
    <CircularProgress sx={{ mt: 2 }} />
  </Box>
);

const SuggestedQuestions = ({ questions, onQuestionClick }) => {
  if (!questions || questions.length === 0) return null;

  return (
    <Box sx={{ mb: 2 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 1 }}>
        <QuestionMarkIcon fontSize="small" sx={{ mr: 1, color: 'primary.main' }} />
        <Typography variant="subtitle2" color="primary.main">
          Suggested follow-up questions
        </Typography>
      </Box>

      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
        {questions.map((question, idx) => (
          <Box
            key={idx}
            onClick={() => onQuestionClick(question)}
            sx={{
              width: '100%',
              px: 2,
              py: 1,
              borderRadius: '16px',
              bgcolor: 'primary.light',
              color: 'white',
              maxWidth: '900px',
              whiteSpace: 'normal',
              wordBreak: 'break-word',
              cursor: 'pointer',
              fontWeight: 400,
              transition: 'all 0.2s',
              '&:hover': {
                bgcolor: 'primary.main',
                boxShadow: 1,
              },
            }}
          >
            {question}
          </Box>
        ))}
      </Box>
    </Box>
  );
};

export default function SecondScreen({ onBackToWizard }) {

  const [driver, setDriver] = useState(null);
  const [nodes, setNodes] = useState([]);
  const [relationships, setRelationships] = useState([]);
  const [graphClusters, setGraphClusters] = useState([]);
  const [chatClusters, setChatClusters] = useState([]);
  const [currentClusterIndex, setCurrentClusterIndex] = useState(0);
  const [lastQuery, setLastQuery] = useState('');
  const [secondLastQuery, setSecondLastQuery] = useState('');

  // Add notification state for clipboard copy
  const [snackbarOpen, setSnackbarOpen] = useState(false);
  const [snackbarMessage, setSnackbarMessage] = useState('');

  // Create theme
  const theme = createTheme({
    palette: {
      primary: { main: '#673ab7' }, // Deep Purple
      secondary: { main: '#9c27b0' }, // Purple
      background: { default: '#f5f5f5' },
    },
  });

  // 1) Use the shared model config hook
  const {
    config,
    setConfig,
    isSaving: configSaving,
    saveStatus: configSaveStatus,
    saveConfigToServer,
  } = useModelConfig();

  // For checking if there's already a config on the server
  const [checkingConfig, setCheckingConfig] = useState(true);
  const [hasConfig, setHasConfig] = useState(false);
  const [configError, setConfigError] = useState(null);

  // Check if we have a config on mount
  useEffect(() => {
    const checkConfig = async () => {
      try {
        // The hook itself fetches on mount, but we do a HEAD check to see if config exists
        const response = await fetch('http://localhost:8080/api/getInferenceConfig');
        if (response.status === 404) {
          // No config yet
          setHasConfig(false);
        } else if (!response.ok) {
          throw new Error(`Server error: ${response.status}`);
        } else {
          // We do have a config
          setHasConfig(true);
        }
      } catch (err) {
        console.warn('Error checking config:', err);
        setConfigError(err.message);
      } finally {
        setCheckingConfig(false);
      }
    };
    checkConfig();
  }, []);

  // When the user saves a new config from the panel
  const handleSaveConfig = async () => {
    setConfigError(null);
    try {
      // The hook handles the actual saving + dev key insertion
      const success = await saveConfigToServer();
      if (success) {
        setHasConfig(true);
      }
    } catch (err) {
      setConfigError(err.message);
    }
  };

  useEffect(() => {
    const loadDriver = async () => {
      try {
        const res = await fetch('http://localhost:8080/api/getNeo4jConnection');
        if (!res.ok) throw new Error('Failed to load Neo4j connection config');
        const { url, user, password } = await res.json();

        const newDriver = neo4j.driver(
          url,
          neo4j.auth.basic(user, password),
          { encrypted: 'ENCRYPTION_OFF', trust: 'TRUST_ALL_CERTIFICATES' }
        );
        await newDriver.verifyConnectivity();
        setDriver(newDriver);
      } catch (err) {
        console.error('Error loading Neo4j driver:', err);
      }
    };

    loadDriver();

    return () => {
      if (driver) driver.close();
    };
  }, []);

  // 2) Search vs. Talk states
  const [mode, setMode] = useState('search');
  const [userInput, setUserInput] = useState('');

  // SEARCH mode
  const [searchResults, setSearchResults] = useState([]);
  const [searchError, setSearchError] = useState(null);
  const [isSearching, setIsSearching] = useState(false);

  // TALK mode (not included in V1)
  const [chatMessages, setChatMessages] = useState([]);
  const [isThinking, setIsThinking] = useState(false);
  const [viewMode, setViewMode] = useState('chat');

  // New state for suggested questions
  const [suggestedQuestions, setSuggestedQuestions] = useState([]);

  // Function to copy text to clipboard
  const copyToClipboard = (text, type) => {
    navigator.clipboard.writeText(text)
      .then(() => {
        setSnackbarMessage(`${type} query copied to clipboard`);
        setSnackbarOpen(true);
      })
      .catch(err => {
        console.error('Failed to copy text: ', err);
        setSnackbarMessage('Failed to copy to clipboard');
        setSnackbarOpen(true);
      });
  };

  // Handle snackbar close
  const handleSnackbarClose = () => {
    setSnackbarOpen(false);
  };

  // -------------------------------------------------------
  // JSON PARSING LOGIC HERE
  // -------------------------------------------------------

  // get full graph data from nodes and rels ids alone
  const loadCluster = async (clusters, index) => {

    if (!driver) {
      console.warn('Neo4j driver not initialized');
      return;
    }    

    console.log('loadCluster called with:', {
      clusters,
      index,
      clusterAt: clusters && clusters[index]
    });

    const { nodes: rawNodes, rels: rawRels } = clusters[index];
    const nodeIds = rawNodes.map(n => Number(n.id));
    // const relIds = rawRels.map(r => Number(r.id));
    const relIds = rawRels.map(r => r.id);

    const session = driver.session();
    try {
      const nodeQuery = `
        MATCH (n)
        WHERE id(n) IN $ids
        RETURN id(n) AS id,
              coalesce(n.name, n.path, 'Node ' + id(n)) AS label,
              labels(n) AS labels,
              properties(n) AS props,
              elementId(n) AS elementId
      `;

      const relQuery = `
        MATCH (a)-[r]-(b)
        WHERE id(a) IN $nodeIds AND id(b) IN $nodeIds AND id(a) < id(b)
        RETURN id(r) AS id,
              id(a) AS from,
              id(b) AS to,
              type(r) AS type
      `;

      const enrichedNodesRes = await session.run(nodeQuery, { ids: nodeIds });
      const enrichedRelsRes = await session.run(relQuery, { nodeIds });

      const EXCLUDED_KEYS = ['embedding'];

      const enrichedNodes = enrichedNodesRes.records.map(r => {
        const rawProps = r.get('props');
        const filteredProps = Object.fromEntries(
          Object.entries(rawProps).filter(([key]) => !EXCLUDED_KEYS.includes(key))
        );

        return {
          id: r.get('id').toNumber?.() ?? r.get('id'),
          labels: r.get('labels'),
          properties: filteredProps,
          label: filteredProps.name || filteredProps.path || `Node ${r.get('id')}`
        };
      });

      const enrichedRels = enrichedRelsRes.records.map(r => ({
        id: r.get('id').toNumber?.() ?? r.get('id'),
        from: r.get('from').toNumber?.() ?? r.get('from'),
        to: r.get('to').toNumber?.() ?? r.get('to'),
        type: r.get('type')
      }));

      setNodes(enrichedNodes);
      setRelationships(enrichedRels);
    } catch (err) {
      console.error('[Graph Enrich] Failed to enrich cluster:', err);
      setNodes([]);
      setRelationships([]);
    } finally {
      await session.close();
    }
  };

  // utils: send messages to your own backend which proxies to OpenAI
  async function callOpenAI(messages) {
    const res = await fetch('http://localhost:8080/api/openaiChat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ messages }),
    });
    if (!res.ok) {
      const errText = await res.text();
      throw new Error(`OpenAI error ${res.status}: ${errText}`);
    }
    const { reply } = await res.json();
    return reply;
  }

  // Generate suggested questions based on LLM reply
  const generateSuggestedQuestions = async (reply, query) => {
    try {
      const systemMsg = {
        role: 'system',
        content: `You are Maxwell an ai assitant that generates follow up questions. Here is the scenario: 
        There is a human user who has a query about a codebase. The human has given that query to an ai assistant named Kevin. 
        Kevin has been given that users query and atempted to answer it using his limited knowledge of the codebase. 
        Carefully compare Kevin’s answer with the user’s query. Identify any parts of the user’s query that Kevin didn’t 
        fully or clearly address. Then write follow-up questions that would help complete or clarify the original query. 
        Output your response as a JSON array of strings. Example: ["question", "question", "question"]`
      };
      
      const userMsg = {
        role: 'user',
        content: `Human user query about a codebase:\n\n"${query}"\n\nKevin the ai assistant's answer:\n\n"${reply}"\n\nGenerate 3-5 concise follow-up questions.`
      };
      
      const questionsText = await callOpenAI([systemMsg, userMsg]);
      
      // Parse the questions from the response
      // const questionsList = questionsText
      //   .split(/\d+\.|\n-|\n•/)  // Split by numbered lists, bullet points, etc.
      //   .map(q => q.trim())
      //   .filter(q => q && q.length > 5 && q.length < 60 && q.endsWith('?')) // Only keep proper questions
      //   .slice(0, 5);  // Limit to 5 questions

      const questionsList = JSON.parse(questionsText).slice(0, 3);
        
      setSuggestedQuestions(questionsList);
    } catch (err) {
      console.error('[Suggestions] Failed to generate questions:', err);
      setSuggestedQuestions([]);
    }
  };

  const latestReq = useRef(0);

  const handleClusterChange = (newIndex) => {
    // bump the request ID
    latestReq.current += 1;
    const reqId = latestReq.current;

    setCurrentClusterIndex(newIndex);
    setChatMessages([]);
    setSuggestedQuestions([]);

    ; (async () => {
      await loadCluster(graphClusters, newIndex);
      // if a newer click happened, bail out
      if (reqId !== latestReq.current) return;

      // pass reqId through so generateBotReply can also guard
      await generateBotReply(chatClusters, newIndex, lastQuery, reqId);
    })();
  };

  // Builds a prompt from a cluster + original query, calls OpenAI, and appends into chatMessages.
  const generateBotReply = async (chatData, clusterIndex, originalQuery, reqId) => {

    // saftey agains flurry clicks on prev and next
    if (reqId !== latestReq.current) return;
    
    setSuggestedQuestions([]);

    // const tokenLimit = ((Number(config.llm?.tpr)) * 4) - 750 || 40000;

    const tokenLimit = (() => {
      const parsed = Number(config?.llm?.tpr);
      if (isNaN(parsed) || parsed <= 0) return 40000;
      return (parsed * 4) - 1000;
    })();

    console.log("Here is llm prompt token lim --> " + tokenLimit);

    const clusterInfo = chatData[clusterIndex];

    const rawMethods = clusterInfo.nodes
      .filter(n => n.labels.includes("Method"))
      .map(n => {
        const { name, file, code } = n.properties;
        return { name, file, code };
      });

    const shaped = { methods: rawMethods };
    let compactNoSpace = JSON.stringify(shaped).replace(/\s+/g, "");

    // if we're over token lim, drop one method at a time
    while (compactNoSpace.length > tokenLimit && rawMethods.length > 0) {
      rawMethods.pop();                         // remove last item
      const tmp = { methods: rawMethods };      // re-wrap
      compactNoSpace = JSON.stringify(tmp)
        .replace(/\s+/g, "");    // re-serialize & strip
    }

    const systemMsg = {
      role: 'system',
      content: `You are kevin, a software engineer. You are a real code-wiz: few programmers are as talented as you at understanding codebases. Your users have the following problems:\n 
        1. They do not know how something works inside a codebase.\n 
        2. They do not know where something is inside a codebase.\n
        Users will ask you a question. Your job is to understand which problem the user has, based on their question, and answer it using whatever context you are provided. 
        You will be provided with an intelligently selected subsection of code from a larger codebase. Use the code you are provided as context to answer the user's question.
        When answering a user's question follow these rules:\n
        1. Always use the provided code context to answer the user's question to the best of your abilities.\n
        2. Always refference the name of the method when refferencing specific bits of code in your answer.\n
        3. Always attempt to answer the question.`
    };
    const userMsg = {
      role: 'user',
      content: `Selected methods:\n${compactNoSpace}\n\nUser question: "${originalQuery}"`,
    };

    setIsThinking(true);
    try {
      const text = await callOpenAI([systemMsg, userMsg]);
      if (reqId !== latestReq.current) return;
      setChatMessages(prev => [...prev, { role: 'assistant', text }]);
      
      // Generate suggested questions after LLM reply is ready
      await generateSuggestedQuestions(text, originalQuery);

    } catch (err) {
      if (reqId !== latestReq.current) return;
      setChatMessages(prev => [...prev, { role: 'assistant', text: `Error: ${err.message}` }]);
    } finally {
      if (reqId === latestReq.current) {
        setIsThinking(false);
      }
    }
  };

  // Handle clicking on a suggested question
  const handleSuggestedQuestionClick = (question) => {
    setUserInput(question);
    handleSubmit(question);
  };

  const handleSubmit = async () => {
    const trimmed = userInput.trim();
    if (!trimmed) return;

    if (mode === 'search') {
      setSearchError(null);
      setIsSearching(true);
      setSuggestedQuestions([]);

      try {
        const response = await fetch('http://localhost:8080/api/query', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ prompt: trimmed }),
        });
        if (!response.ok) {
          const errorText = await response.text();
          throw new Error(`Query failed: ${response.status} - ${errorText}`);
        }

        const json = await response.json();
        const { chat, graph } = json;

        setGraphClusters(graph);
        setChatClusters(chat);

        const reqId = ++latestReq.current;
        setCurrentClusterIndex(0);
        setChatMessages([]);
        
        setSecondLastQuery(lastQuery);
        setLastQuery(trimmed);
        await loadCluster(graph, 0);

        // thinking setting done in the helper
        await generateBotReply(chat, 0, trimmed, reqId);
        // setChatMessages((prev) => [...prev, botReply]);

      } catch (err) {
        console.error('[Submit] Search error:', err);
        setSearchError(err.message);
      } finally {
        setIsSearching(false);
      }

    } else {

      // mode === 'talk': NOT IMPLIMENTED IN V1
      const userMsg = { role: 'user', text: trimmed };
      setChatMessages((prev) => [...prev, userMsg]);
      setIsThinking(true);
      setTimeout(() => {
        const botReply = {
          role: 'assistant',
          text: `I received your message: "${trimmed}". (Placeholder LLM response for chat)`,
        };
        setChatMessages((prev) => [...prev, botReply]);
        setIsThinking(false);
      }, 1500);
    }
    setUserInput('');
    setLastQuery(trimmed);
  };

  // A) If we're still checking config, show a spinner
  if (checkingConfig) {
    return (
      <ThemeProvider theme={theme}>
        <LoadingScreen message="Checking inference config..." />
      </ThemeProvider>
    );
  }

  // B) If no config yet, show ModelConfigPanel
  if (!hasConfig) {
    return (
      <ThemeProvider theme={theme}>
        <Box sx={{ p: 3 }}>
          {configError && (
            <Alert severity="warning" sx={{ mb: 2 }}>
              {configError}
            </Alert>
          )}
          <Alert severity="info" sx={{ mb: 2 }}>
            No inference config found. Please set it up below.
          </Alert>
          <ModelConfigPanel
            config={config}
            onConfigChange={setConfig}
            isSaving={configSaving}
            saveStatus={configSaveStatus}
            onSaveConfig={handleSaveConfig}
          />
        </Box>
      </ThemeProvider>
    );
  }

  // C) Otherwise, show the normal "Search vs. Talk"
  return (
    <ThemeProvider theme={theme}>
      <Box
        sx={{
          display: 'flex',
          flexDirection: 'column',
          height: '100vh',
          width: '100%',
          bgcolor: 'background.default',
        }}
      >

        {/* ---------------------------------------------------------- */}
        {/* banner section */}
        {/* ---------------------------------------------------------- */}
        <Box
          sx={{
            py: 1,                    // ← 8px top/bottom
            px: 2,                    // ← 16px left/right
            minHeight: 30,
            borderBottom: '1px solid',
            borderColor: 'divider',
            bgcolor: 'background.paper',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
          }}
        >
          <Box
            component="img"
            src={vectorImg}
            alt="Purple Search"
            sx={{ width: 50, mb: 0, ml: 1, mr: 1 }}
          />

          <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }} >


            {/* "Connected" chips */}
            <Chip
              icon={
                <div
                  style={{
                    width: 10,
                    height: 10,
                    background: '#4caf50',
                    borderRadius: '50%',
                  }}
                />
              }
              label="Server Connected"
              variant="outlined"
              color="success"
              size="small"
              sx={{ mr: 1 }}
            />
            <Chip
              icon={
                <div
                  style={{
                    width: 10,
                    height: 10,
                    background: '#4caf50',
                    borderRadius: '50%',
                  }}
                />
              }
              label="Neo4j Connected"
              variant="outlined"
              color="success"
              size="small"
            />
            <Button
              variant="outlined"
              color="primary"
              onClick={onBackToWizard}
              sx={{ borderRadius: 2 }}
            >
              Back to Settings
            </Button>
          </Box>
        </Box>


        {/* ---------------------------------------------------------- */}
        {/* Main content aka split panel section */}
        {/* ---------------------------------------------------------- */}
        <Box sx={{ flex: 1, display: 'flex', flexDirection: 'row', alignItems: 'stretch', minHeight: 0, }}>

          <Box
            sx={{
              flex: 1,
              borderRight: '1px solid',
              borderColor: 'divider',
              p: 2,
              overflow: 'auto',
              bgcolor: 'background.paper',
            }}
          >

            {/* ---------------------------------------------------------- */}
            {/* Left Panel */}
            {/* ---------------------------------------------------------- */}

            <Card
              elevation={0}
              sx={{
                height: '100%',
                width: '100%',
                display: 'flex',
                flexDirection: 'column',
              }}
            >
              <CardContent
                sx={{
                  p: 2,
                  borderBottom: '1px solid',
                  borderColor: 'divider',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between', // Add this to push items to the edges
                }}
              >
                <Box sx={{ display: 'flex', alignItems: 'center' }}>
                  <SearchIcon color="primary" sx={{ mr: 1 }} />
                  <Typography variant="h6" color="primary">
                    Graph Search
                  </Typography>
                </Box>
                
                {/* Add the query buttons here */}
                <Box sx={{ display: 'flex', gap: 1 }}>
                  <Tooltip title={secondLastQuery || "No previous query"} arrow>
                    <Button
                      variant="outlined"
                      size="small"
                      onClick={() => copyToClipboard(secondLastQuery, "Previous")}
                      disabled={!secondLastQuery}
                      sx={{ 
                        color: '#d32f2f', 
                        borderColor: '#d32f2f',
                        '&:hover': { 
                          borderColor: '#b71c1c',
                          backgroundColor: 'rgba(211, 47, 47, 0.04)' 
                        }
                      }}
                    >
                      prevQ
                    </Button>
                  </Tooltip>
                  <Tooltip title={lastQuery || "No current query"} arrow>
                    <Button
                      variant="outlined"
                      size="small"
                      onClick={() => copyToClipboard(lastQuery, "Current")}
                      disabled={!lastQuery}
                      sx={{ 
                        color: '#2e7d32', 
                        borderColor: '#2e7d32',
                        '&:hover': { 
                          borderColor: '#1b5e20',
                          backgroundColor: 'rgba(46, 125, 50, 0.04)' 
                        }
                      }}
                    >
                      curQ
                    </Button>
                  </Tooltip>
                </Box>
              </CardContent>

              {/* Single wrapper that fills the remaining height */}
              <Box
                sx={{
                  flex: 1,
                  p: 2,
                  position: 'relative',  // anchors the label
                  height: '100%',        // ensures it actually stretches
                }}
              >
                {searchError ? (
                  <Alert severity="error" sx={{ mb: 2 }}>
                    {searchError}
                  </Alert>
                ) : graphClusters.length === 0 ? (
                  <Typography variant="h6" color="primary">
                    No results
                  </Typography>
                ) : (
                  <>
                    {/* 1) Floating label */}
                    <Box
                      sx={{
                        position: 'absolute',
                        top: 8,
                        left: 12,
                        zIndex: 2,
                      }}
                    >
                      <Typography variant="caption" color="text.secondary">
                        Cluster {currentClusterIndex + 1} of {graphClusters.length}
                      </Typography>
                    </Box>

                    {/* 2) GraphViewer fills the entire wrapper */}
                    <GraphViewer nodes={nodes} relationships={relationships} />
                  </>
                )}
              </Box>
            </Card>
          </Box>

          {/* ---------------------------------------------------------- */}
          {/* right Panel */}
          {/* ---------------------------------------------------------- */}

          <Box
            sx={{
              flex: 1,
              display: 'flex',
              borderRight: '1px solid',
              borderColor: 'divider',
              p: 2,
              overflow: 'hidden',
              bgcolor: 'background.paper',
              minHeight: 0,
            }}
          >
            <Card elevation={0} sx={{ height: '100%', width: '100%', display: 'flex', flexDirection: 'column', minHeight: 0, }}>
              <CardContent sx={{ p: 2, borderBottom: '1px solid', borderColor: 'divider', display: 'flex', alignItems: 'center' }}>
                <ChatIcon color="primary" sx={{ mr: 1 }} />
                <Typography variant="h6" color="primary">LLM</Typography>
              </CardContent>
              <Box sx={{ flex: 1, p: 2, overflowY: 'auto', minHeight: 0, }}>

                {chatMessages.length === 0 && !isThinking ? (
                  <Box sx={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', flexDirection: 'column', color: 'text.secondary' }}>
                    <Typography >No queries yet</Typography>
                    <Typography >LLM analysis of currently viewed cluster will apear here</Typography>
                  </Box>
                ) : (
                  <>
                    
                    {/* Chat messages */}
                    {chatMessages.map((msg, idx) => (
                      <Box key={idx} sx={{ mb: 2, display: 'flex', flexDirection: 'column', alignItems: msg.role === 'user' ? 'flex-end' : 'flex-start' }}>
                        <Paper
                          sx={{
                            p: 2,
                            borderRadius: 2,
                            bgcolor: msg.role === 'user' ? 'primary.light' : 'background.paper',
                            color: msg.role === 'user' ? 'white' : 'text.primary',
                            maxWidth: '100%',             // never wider than 80% of panel
                            whiteSpace: 'pre-wrap',      // preserve newlines but wrap long words
                            wordBreak: 'break-word',     // break very long words/URLs
                            overflowWrap: 'break-word',  // extra safety
                          }}>
                          <Typography variant="body2" sx={{ wordBreak: 'break-word' }}>
                            {msg.text}
                          </Typography>
                        </Paper>
                      </Box>
                    ))}
                    {/* Suggested Questions - only shown when there's a finished reply and questions available */}
                    {!isThinking && suggestedQuestions.length > 0 && (
                      <SuggestedQuestions 
                        questions={suggestedQuestions} 
                        onQuestionClick={handleSuggestedQuestionClick} 
                      />
                    )}
                  </>
                )}
                {isThinking && (
                  <Box sx={{ display: 'flex', mt: 2 }}>
                    <Paper sx={{ p: 2, borderRadius: 2, display: 'flex', alignItems: 'center' }}>
                      <CircularProgress size={20} sx={{ mr: 2 }} />
                      <Typography variant="body2">Assistant is thinking...</Typography>
                    </Paper>
                  </Box>
                )}
              </Box>
            </Card>
          </Box>
        </Box>

        {/* ---------------------------------------------------------- */}
        {/* bottom tool bar */}
        {/* ---------------------------------------------------------- */}

        <Box
          sx={{
            p: 2,
            borderTop: '1px solid',
            borderColor: 'divider',
            display: 'flex',
            alignItems: 'center',
            gap: 2,
            bgcolor: 'background.paper',
          }}
        >

          <Button
            variant="contained"
            color="primary"
            onClick={() => {
              const prev = (currentClusterIndex - 1 + graphClusters.length) % graphClusters.length;
              handleClusterChange(prev);
            }}
            sx={{ borderRadius: 2 }}
          >
            Prev
          </Button>
          <Button
            variant="contained"
            color="primary"
            onClick={() => {
              const next = (currentClusterIndex + 1) % graphClusters.length;
              handleClusterChange(next);
            }}
            sx={{ borderRadius: 2 }}
          >
            Next
          </Button>

          <TextField
            variant="outlined"
            size="small"
            placeholder={mode === 'search' ? 'Enter your search query...' : 'Chat with the LLM...'}
            sx={{
              flex: 1,
              minWidth: 100,                  // ← never shrink below 300px
              '& .MuiOutlinedInput-root': {
                borderRadius: 2,
                '&.Mui-focused fieldset': { borderColor: 'primary.main' }
              }
            }}
            value={userInput}
            onChange={(e) => setUserInput(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') handleSubmit(); }}
          />
          <Button
            variant="contained"
            color="primary"
            onClick={handleSubmit}
            disabled={!userInput.trim() || isSearching || isThinking}
            endIcon={<SendIcon />}
            sx={{ borderRadius: 2, px: 2, minWidth: '100px', height: '40px', boxShadow: 2 }}
          >
            {isSearching || isThinking ? 'Processing...' : 'Submit'}
          </Button>
        </Box>
      </Box>
    </ThemeProvider>
  );
}

