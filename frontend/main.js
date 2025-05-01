const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('path');

function createWindow() {
  const mainWindow = new BrowserWindow({
    width: 1200,
    height: 800,
    webPreferences: {
      nodeIntegration: true,
      contextIsolation: false,
    },
  });
  mainWindow.loadURL(`file://${path.join(__dirname, 'build', 'index.html')}`);
}

app.whenReady().then(() => {
  createWindow();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

ipcMain.handle('process-uploads', async (event, data) => {
  try {
    return 'Processing completed!';
  } catch (error) {
    return 'Processing failed.';
  }
});

ipcMain.handle('open-file-or-folder-dialog', async () => {
  const result = await dialog.showOpenDialog({
    properties: ['openFile', 'openDirectory', 'multiSelections']
  });
  return result.filePaths || [];
});
