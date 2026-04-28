import editorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker'

let installed = false

export function ensureMonacoEnvironment() {
  if (installed) {
    return
  }

  self.MonacoEnvironment = {
    getWorker() {
      return new editorWorker()
    }
  }

  installed = true
}
