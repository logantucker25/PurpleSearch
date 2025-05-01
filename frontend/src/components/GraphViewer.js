import React, { useEffect, useRef, useState } from 'react';
import { DataSet, Network } from 'vis-network/standalone';
import 'vis-network/styles/vis-network.css';

export default function GraphViewer({ nodes = [], relationships = [] }) {
  const containerRef = useRef(null);
  const popupRef = useRef(null);

  const [selectedNode, setSelectedNode] = useState(null);
  const [popupPos, setPopupPos] = useState({ top: 100, left: 100 });
  const [isDragging, setIsDragging] = useState(false);
  const [dragOffset, setDragOffset] = useState({ x: 0, y: 0 });

  // color ledgend
  const labelToColor = {
    Method: '#f9ae7f',
    Class: '#ef7c91',
    Field: '#a3a3ff',
    Variable: '#2196f3',
    File: '#fffcbf',
    Directory: '#bdf796',
    Other: '#9e9e9e',
  };

  useEffect(() => {
    if (!containerRef.current) return;

    // package info for each node
    const neo4jNodeMap = {};
    const enrichedNodes = nodes.map((node) => {
      const type = node.labels?.[0] || 'Other';
      const color = labelToColor[type] || labelToColor.Other;

      const fullLabel = node.properties?.simple_name
        || node.properties?.name
        || node.properties?.path
        || `Node ${node.id}`;

      const displayLabel =
        fullLabel.length > 20
          ? fullLabel.slice(-20)
          : fullLabel;

      neo4jNodeMap[node.id] = {
        ...node,
        type,
        name: node.name || node.properties?.name || `Node ${node.id}`
      };

      return {
        id: node.id,
        label: displayLabel || node.properties?.name || `Node ${node.id}`,
        color,
        shape: 'circle',
        borderWidth: 0,
        size: 30,
        scaling: {
          min: 20,
          max: 40,
          label: true
        },
        font: {
          size: 11,
        }
      };
    });

    const nodeSet = new DataSet(enrichedNodes);

    // must deduple edges or face error (graph viz is sensitive)
    const uniqueRels = Array.from(
      new Map(relationships.map(r => [r.id, r])).values()
    );
    const edgeSet = new DataSet(uniqueRels);
    
    const data = { nodes: nodeSet, edges: edgeSet };

    // graph physics
    const options = {
      layout: { hierarchical: false },
      edges: { color: '#000000' },
      physics: {
        solver: 'forceAtlas2Based',
        forceAtlas2Based: {
          gravitationalConstant: -15,
          centralGravity: 0.01,
          damping: 0.5,
          avoidOverlap: 1
        },
        stabilization: { iterations: 200 }
      }
    };

    const network = new Network(containerRef.current, data, options);

    network.on('click', function (params) {
      if (params.nodes.length > 0) {
        const nodeId = params.nodes[0];
        const fullNode = neo4jNodeMap[nodeId] || null;
        setSelectedNode(fullNode);
        setPopupPos({ top: 100, left: 100 });
      }
    });

    return () => network.destroy();
  }, [nodes, relationships]);

  const handleMouseDown = (e) => {
    if (!popupRef.current || !containerRef.current) return;

    const popupRect = popupRef.current.getBoundingClientRect();
    const containerRect = containerRef.current.getBoundingClientRect();

    // Calculate initial drag offset inside the popup (relative to the container)
    setDragOffset({
      x: e.clientX - popupRect.left,
      y: e.clientY - popupRect.top,
      containerX: containerRect.left,
      containerY: containerRect.top
    });

    setIsDragging(true);
  };


  useEffect(() => {
    const handleMouseMove = (e) => {
      if (isDragging) {
        setPopupPos({
          top: e.clientY - dragOffset.y - dragOffset.containerY,
          left: e.clientX - dragOffset.x - dragOffset.containerX,
        });
      }
    };
    const handleMouseUp = () => setIsDragging(false);

    window.addEventListener('mousemove', handleMouseMove);
    window.addEventListener('mouseup', handleMouseUp);
    return () => {
      window.removeEventListener('mousemove', handleMouseMove);
      window.removeEventListener('mouseup', handleMouseUp);
    };
  }, [isDragging, dragOffset]);


  return (
    
    <div style={{ position: 'relative', height: '100%', width: '100%' }}>
      <div
        ref={containerRef}
        style={{
          position: 'absolute',
          top: 0, left: 0, bottom: 0, right: 0,
          backgroundColor: '#fff', zIndex: 1
        }}
      />

      {/* Legend */}
      <div style={{
        position: 'absolute', top: 10, left: -15, zIndex: 2,
        background: 'rgba(255,255,255,255)',
        padding: '8px 12px',
        borderWidth: 0,
        borderRadius: 3,
        fontSize: '12px'
      }}>
        <div><span style={{ color: '#f9ae7f' }}>●</span> Method</div>
        <div><span style={{ color: '#ef7c91' }}>●</span> Class</div>
        <div><span style={{ color: '#a3a3ff' }}>●</span> Field</div>
        <div><span style={{ color: '#fffcbf' }}>●</span> File</div>
        <div><span style={{ color: '#bdf796' }}>●</span> Directory</div>
        <div><span style={{ color: '#9e9e9e' }}>●</span> Other</div>
      </div>

      {/* Draggable Popup */}
      {selectedNode && (
        <div
          ref={popupRef}
          onMouseDown={handleMouseDown}
          style={{
            position: 'absolute',
            top: popupPos.top,
            left: popupPos.left,
            width: '350px',
            maxHeight: 'calc(100vh - 120px)',
            overflowY: 'auto',
            background: 'white',
            padding: '12px',
            border: '1px solid #ccc',
            borderRadius: '8px',
            zIndex: 3,
            fontSize: '14px',
            cursor: isDragging ? 'grabbing' : 'grab',
            userSelect: 'none'
          }}
        >

          <pre style={{ whiteSpace: 'pre-wrap', wordWrap: 'break-word' }}>
            {JSON.stringify({
              id: selectedNode.id,
              elementId: selectedNode.elementId,
              labels: selectedNode.labels,
              ...Object.fromEntries(
                Object.entries(selectedNode.properties || {}).filter(
                  ([key]) => !['embedding', 'start_line', 'end_line', 'code', 'id'].includes(key)
                )
              )
            }, null, 2)}
          </pre>

          {/* Pretty‑printed code block */}
          {selectedNode.properties?.code && (
            <>
              <strong style={{ display: 'block', marginTop: '12px', color: "#673ab7" }}>Code</strong>
              <pre
                style={{
                  background: 'white',
                  color: 'black',
                  padding: '12px',
                  borderRadius: '4px',
                  whiteSpace: 'pre',
                  overflowX: 'auto',
                  fontFamily: 'monospace',
                  fontSize: '13px',
                  lineHeight: 1.4,
                }}
              >
                {selectedNode.properties.code}
              </pre>
            </>
          )}
          <button
            onClick={() => setSelectedNode(null)}
            style={{
              marginTop: "10px",
              backgroundColor: "#ffffff",
              color: "#673ab7",
              border: "2px solid #673ab7",
              padding: "8px 16px",
              borderRadius: "4px",
              cursor: "pointer",
            }}
          >
            CLOSE
          </button>
        </div>
      )}
    </div>
  );
}