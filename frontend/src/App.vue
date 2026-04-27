<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'

const apiBase = import.meta.env.VITE_API_BASE ?? ''
const editorRef = ref(null)
const consoleRef = ref(null)

const questionId = ref(Number(new URLSearchParams(window.location.search).get('id') || 1))
const questionLoading = ref(false)
const questionError = ref('')
const runLoading = ref(false)
const aiLoading = ref(false)
const language = ref('java')

const questionDetail = reactive({
  question: {
    id: null,
    title: '加载中...',
    content: '',
    difficulty: '',
    timeLimit: 1000,
    memoryLimit: 128
  },
  testCases: []
})

const consoleState = reactive({
  status: 'Idle',
  output: '',
  message: '等待运行',
  aiAdvice: '点击“求助通义千问”后，辅导内容会在这里流式出现。',
  logs: [
    {
      id: crypto.randomUUID(),
      type: 'system',
      title: 'System',
      content: 'Editor online. Ready to judge.'
    }
  ]
})

const codeTemplates = reactive({
  java: `import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        int a = scanner.nextInt();
        int b = scanner.nextInt();
        System.out.println(a + b);
    }
}
`,
  cpp: `#include <iostream>
using namespace std;

int main() {
    int a, b;
    cin >> a >> b;
    cout << a + b << endl;
    return 0;
}
`
})

let monaco
let editor
let abortAiStream

const difficultyTone = computed(() => {
  const difficulty = (questionDetail.question.difficulty || '').toLowerCase()
  if (difficulty.includes('easy') || difficulty.includes('简单')) {
    return 'border-emerald-400/30 bg-emerald-400/10 text-emerald-200'
  }
  if (difficulty.includes('hard') || difficulty.includes('困难')) {
    return 'border-pink-400/30 bg-pink-400/10 text-pink-200'
  }
  return 'border-cyan-400/30 bg-cyan-400/10 text-cyan-100'
})

const currentCode = () => (editor ? editor.getValue() : codeTemplates[language.value])

function buildApiUrl(path) {
  return `${apiBase}${path}`
}

function pushLog(type, title, content) {
  consoleState.logs.push({
    id: crypto.randomUUID(),
    type,
    title,
    content
  })
  nextTick(scrollConsoleToBottom)
}

function scrollConsoleToBottom() {
  if (!consoleRef.value) {
    return
  }
  consoleRef.value.scrollTop = consoleRef.value.scrollHeight
}

async function initEditor() {
  if (!editorRef.value) {
    return
  }

  monaco = await import('monaco-editor')
  monaco.editor.defineTheme('simple-neon', {
    base: 'vs-dark',
    inherit: true,
    rules: [
      { token: 'keyword', foreground: '55C8FF' },
      { token: 'string', foreground: '4DF5C6' },
      { token: 'number', foreground: 'FFB86C' },
      { token: 'comment', foreground: '6F7CA8' }
    ],
    colors: {
      'editor.background': '#0A1020',
      'editorLineNumber.foreground': '#41507C',
      'editorCursor.foreground': '#4DF5C6',
      'editor.selectionBackground': '#143054',
      'editor.lineHighlightBackground': '#0f1830'
    }
  })

  editor = monaco.editor.create(editorRef.value, {
    value: codeTemplates[language.value],
    language: 'java',
    theme: 'simple-neon',
    automaticLayout: true,
    fontFamily: 'JetBrains Mono, monospace',
    fontSize: 14,
    minimap: { enabled: false },
    scrollBeyondLastLine: false,
    roundedSelection: false,
    padding: { top: 18, bottom: 18 }
  })

  editor.onDidChangeModelContent(() => {
    codeTemplates[language.value] = editor.getValue()
  })
}

watch(language, (nextLanguage, prevLanguage) => {
  if (!editor || !monaco || !prevLanguage) {
    return
  }
  codeTemplates[prevLanguage] = editor.getValue()
  editor.setValue(codeTemplates[nextLanguage])
  monaco.editor.setModelLanguage(editor.getModel(), nextLanguage === 'cpp' ? 'cpp' : 'java')
  pushLog('system', 'Language', `Switched to ${nextLanguage}`)
})

async function loadQuestion() {
  questionLoading.value = true
  questionError.value = ''

  try {
    const response = await fetch(buildApiUrl(`/api/questions/${questionId.value}/detail`))
    const result = await response.json()

    if (!response.ok || result.code !== 0 || !result.data) {
      throw new Error(result.message || '题目加载失败')
    }

    questionDetail.question = result.data.question
    questionDetail.testCases = result.data.testCases || []
    pushLog('success', 'Question', `Loaded question #${questionId.value}`)
  } catch (error) {
    questionError.value = error.message || '题目加载失败'
    pushLog('error', 'Question', questionError.value)
  } finally {
    questionLoading.value = false
  }
}

async function runCode() {
  if (!questionDetail.question.id) {
    pushLog('error', 'Judge', '请先加载题目')
    return
  }

  runLoading.value = true
  consoleState.status = 'Running'
  consoleState.message = '正在调用评测器'
  pushLog('system', 'Judge', `Submitting ${language.value} code for question #${questionDetail.question.id}`)

  try {
    const response = await fetch(buildApiUrl('/api/judge'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        questionId: questionDetail.question.id,
        language: language.value,
        code: currentCode()
      })
    })
    const result = await response.json()

    if (!response.ok || result.code !== 0 || !result.data) {
      throw new Error(result.message || '评测失败')
    }

    consoleState.status = result.data.status
    consoleState.output = result.data.output || ''
    consoleState.message = result.data.message || '评测完成'
    pushLog('success', result.data.status, result.data.message || 'Judge finished')
  } catch (error) {
    consoleState.status = 'Request Failed'
    consoleState.output = ''
    consoleState.message = error.message || '评测失败'
    pushLog('error', 'Judge', consoleState.message)
  } finally {
    runLoading.value = false
  }
}

function parseSseBlock(block) {
  const lines = block.split('\n')
  let eventName = 'message'
  const dataParts = []

  for (const line of lines) {
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataParts.push(line.slice(5).trim())
    }
  }

  return {
    event: eventName,
    data: dataParts.join('\n')
  }
}

async function askAiCoach() {
  if (!questionDetail.question.id) {
    pushLog('error', 'AI', '请先加载题目')
    return
  }

  if (abortAiStream) {
    abortAiStream.abort()
  }

  const controller = new AbortController()
  abortAiStream = controller
  aiLoading.value = true
  consoleState.aiAdvice = ''
  pushLog('system', 'AI', 'Connecting to Qwen stream...')

  try {
    const response = await fetch(buildApiUrl('/api/ai/help'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        questionContent: questionDetail.question.content,
        wrongCode: currentCode(),
        errorOutput: [consoleState.status, consoleState.message, consoleState.output || 'No runtime output'].join('\n')
      }),
      signal: controller.signal
    })

    if (!response.ok || !response.body) {
      throw new Error(`AI 请求失败: HTTP ${response.status}`)
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''

    while (true) {
      const { value, done } = await reader.read()
      if (done) {
        break
      }

      buffer += decoder.decode(value, { stream: true })
      const blocks = buffer.split('\n\n')
      buffer = blocks.pop() || ''

      for (const block of blocks) {
        if (!block.trim()) {
          continue
        }
        const payload = parseSseBlock(block)
        if (payload.event === 'message') {
          consoleState.aiAdvice += payload.data
        } else if (payload.event === 'error') {
          consoleState.aiAdvice += `\n[error] ${payload.data}`
          pushLog('error', 'AI', payload.data)
        } else if (payload.event === 'done') {
          pushLog('success', 'AI', '辅导完成')
        }
        nextTick(scrollConsoleToBottom)
      }
    }
  } catch (error) {
    if (error.name !== 'AbortError') {
      const message = error.message || 'AI 流式请求失败'
      consoleState.aiAdvice += `\n[error] ${message}`
      pushLog('error', 'AI', message)
    }
  } finally {
    aiLoading.value = false
    abortAiStream = null
  }
}

onMounted(async () => {
  await initEditor()
  await loadQuestion()
})

onBeforeUnmount(() => {
  if (editor) {
    editor.dispose()
  }
  if (abortAiStream) {
    abortAiStream.abort()
  }
})
</script>

<template>
  <div class="relative overflow-hidden">
    <div class="pointer-events-none absolute inset-0 bg-grid opacity-20"></div>

    <main class="relative mx-auto flex min-h-screen max-w-[1800px] flex-col px-4 py-5 lg:px-6">
      <header class="mb-5 rounded-[28px] border border-cyan-400/15 bg-slate-950/70 px-5 py-4 shadow-neon glass">
        <div class="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
          <div>
            <p class="font-mono text-xs uppercase tracking-[0.35em] text-cyan-300/80">Simple AI OJ</p>
            <h1 class="mt-2 text-3xl font-bold text-white">极客暗黑做题台</h1>
            <p class="mt-2 max-w-2xl text-sm leading-6 text-slate-300">
              左侧读题，右侧写代码，底部看评测与通义千问辅导。整个页面围绕“连续反馈”来设计，不需要频繁切换上下文。
            </p>
          </div>

          <div class="flex flex-col gap-3 sm:flex-row sm:items-center">
            <label class="flex items-center gap-3 rounded-2xl border border-cyan-400/20 bg-slate-900/80 px-4 py-3">
              <span class="font-mono text-xs uppercase tracking-[0.2em] text-slate-400">Question ID</span>
              <input
                v-model.number="questionId"
                type="number"
                min="1"
                class="w-24 bg-transparent font-mono text-sm text-white outline-none"
              />
            </label>

            <button
              class="rounded-2xl border border-cyan-400/30 bg-cyan-400/10 px-5 py-3 text-sm font-semibold text-cyan-100 transition hover:border-cyan-300/70 hover:bg-cyan-400/20"
              :disabled="questionLoading"
              @click="loadQuestion"
            >
              {{ questionLoading ? '载入中...' : '载入题目' }}
            </button>
          </div>
        </div>
      </header>

      <section class="grid flex-1 gap-5 xl:grid-cols-[minmax(340px,0.9fr)_minmax(500px,1.4fr)]">
        <aside class="rounded-[28px] border border-cyan-400/15 bg-slate-950/75 p-5 shadow-soft glass">
          <div class="mb-4 flex flex-wrap items-center gap-3">
            <span class="rounded-full border border-slate-700/80 bg-slate-900/70 px-3 py-1 font-mono text-xs text-slate-300">
              #{{ questionDetail.question.id ?? '--' }}
            </span>
            <span
              class="rounded-full border px-3 py-1 font-mono text-xs"
              :class="difficultyTone"
            >
              {{ questionDetail.question.difficulty || 'Unknown' }}
            </span>
            <span class="rounded-full border border-slate-700/80 bg-slate-900/70 px-3 py-1 font-mono text-xs text-slate-300">
              {{ questionDetail.question.timeLimit }} ms
            </span>
            <span class="rounded-full border border-slate-700/80 bg-slate-900/70 px-3 py-1 font-mono text-xs text-slate-300">
              {{ questionDetail.question.memoryLimit }} MB
            </span>
          </div>

          <div class="rounded-[24px] border border-cyan-400/10 bg-[#09111f] p-5 panel-grid">
            <h2 class="text-2xl font-bold text-white">{{ questionDetail.question.title }}</h2>
            <p v-if="questionError" class="mt-4 rounded-2xl border border-pink-500/30 bg-pink-500/10 px-4 py-3 text-sm text-pink-100">
              {{ questionError }}
            </p>
            <div class="mt-4 whitespace-pre-wrap text-sm leading-7 text-slate-200">
              {{ questionDetail.question.content || '暂无题面内容。' }}
            </div>
          </div>

          <div class="mt-5">
            <div class="mb-3 flex items-center justify-between">
              <h3 class="text-lg font-semibold text-white">测试用例</h3>
              <span class="font-mono text-xs uppercase tracking-[0.24em] text-slate-500">
                {{ questionDetail.testCases.length }} cases
              </span>
            </div>

            <div class="space-y-3">
              <article
                v-for="testCase in questionDetail.testCases"
                :key="testCase.id"
                class="rounded-[22px] border border-cyan-400/10 bg-slate-950/70 p-4"
              >
                <div class="mb-3 flex items-center justify-between">
                  <span class="font-mono text-xs uppercase tracking-[0.24em] text-cyan-200">Case {{ testCase.id }}</span>
                </div>
                <div class="grid gap-3 md:grid-cols-2">
                  <div class="rounded-2xl border border-slate-800 bg-slate-900/80 p-3">
                    <div class="mb-2 font-mono text-[11px] uppercase tracking-[0.22em] text-slate-500">Input</div>
                    <pre class="whitespace-pre-wrap font-mono text-sm text-slate-200">{{ testCase.input }}</pre>
                  </div>
                  <div class="rounded-2xl border border-slate-800 bg-slate-900/80 p-3">
                    <div class="mb-2 font-mono text-[11px] uppercase tracking-[0.22em] text-slate-500">Expected</div>
                    <pre class="whitespace-pre-wrap font-mono text-sm text-slate-200">{{ testCase.expectedOutput }}</pre>
                  </div>
                </div>
              </article>
            </div>
          </div>
        </aside>

        <section class="grid min-h-[780px] gap-5 grid-rows-[minmax(420px,1fr)_minmax(260px,0.72fr)]">
          <div class="rounded-[28px] border border-cyan-400/15 bg-slate-950/75 p-4 shadow-neon glass">
            <div class="mb-4 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
              <div>
                <p class="font-mono text-xs uppercase tracking-[0.3em] text-cyan-300/70">Code Arena</p>
                <h3 class="mt-2 text-xl font-semibold text-white">Monaco Editor</h3>
              </div>

              <div class="flex flex-wrap items-center gap-3">
                <select
                  v-model="language"
                  class="rounded-2xl border border-slate-700 bg-slate-900 px-4 py-3 font-mono text-sm text-slate-100 outline-none"
                >
                  <option value="java">Java</option>
                  <option value="cpp">C++</option>
                </select>

                <button
                  class="rounded-2xl border border-emerald-400/30 bg-emerald-400/10 px-5 py-3 text-sm font-semibold text-emerald-100 transition hover:border-emerald-300/70 hover:bg-emerald-400/20"
                  :disabled="runLoading"
                  @click="runCode"
                >
                  {{ runLoading ? '评测中...' : '运行评测' }}
                </button>

                <button
                  class="rounded-2xl border border-pink-400/30 bg-pink-400/10 px-5 py-3 text-sm font-semibold text-pink-100 transition hover:border-pink-300/70 hover:bg-pink-400/20"
                  :disabled="aiLoading"
                  @click="askAiCoach"
                >
                  {{ aiLoading ? '求助中...' : '求助通义千问' }}
                </button>
              </div>
            </div>

            <div class="mb-4 h-1.5 overflow-hidden rounded-full bg-slate-900">
              <div class="h-full w-full origin-left rounded-full bg-gradient-to-r from-cyan-400 via-emerald-300 to-pink-400 animate-pulsebar"></div>
            </div>

            <div ref="editorRef" class="h-[calc(100%-88px)] min-h-[330px] overflow-hidden rounded-[22px] border border-cyan-400/10"></div>
          </div>

          <div class="rounded-[28px] border border-cyan-400/15 bg-slate-950/80 p-4 shadow-soft glass">
            <div class="mb-4 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
              <div>
                <p class="font-mono text-xs uppercase tracking-[0.3em] text-slate-500">Console Matrix</p>
                <h3 class="mt-2 text-xl font-semibold text-white">运行结果与 AI 辅导</h3>
              </div>

              <div class="flex flex-wrap items-center gap-3">
                <span class="rounded-full border border-cyan-400/20 bg-cyan-400/10 px-3 py-1 font-mono text-xs uppercase tracking-[0.22em] text-cyan-100">
                  {{ consoleState.status }}
                </span>
                <span class="rounded-full border border-slate-700 bg-slate-900 px-3 py-1 font-mono text-xs uppercase tracking-[0.22em] text-slate-400">
                  {{ aiLoading ? 'AI Streaming' : 'AI Standby' }}
                </span>
              </div>
            </div>

            <div ref="consoleRef" class="grid h-[calc(100%-74px)] gap-4 overflow-y-auto lg:grid-cols-[0.9fr_1.1fr]">
              <section class="rounded-[22px] border border-slate-800 bg-[#08101e] p-4">
                <div class="mb-3 flex items-center justify-between">
                  <span class="font-mono text-[11px] uppercase tracking-[0.24em] text-slate-500">Judge Output</span>
                  <span class="text-xs text-slate-400">{{ consoleState.message }}</span>
                </div>
                <pre class="min-h-28 whitespace-pre-wrap rounded-2xl border border-slate-800 bg-slate-950/90 p-4 font-mono text-sm leading-6 text-slate-200">{{ consoleState.output || '暂无输出' }}</pre>

                <div class="mt-4 space-y-2">
                  <article
                    v-for="item in consoleState.logs"
                    :key="item.id"
                    class="rounded-2xl border px-3 py-2"
                    :class="{
                      'border-cyan-400/15 bg-cyan-400/5': item.type === 'system',
                      'border-emerald-400/15 bg-emerald-400/5': item.type === 'success',
                      'border-pink-400/15 bg-pink-400/5': item.type === 'error'
                    }"
                  >
                    <div class="font-mono text-[11px] uppercase tracking-[0.22em] text-slate-500">{{ item.title }}</div>
                    <p class="mt-1 text-sm text-slate-200">{{ item.content }}</p>
                  </article>
                </div>
              </section>

              <section class="rounded-[22px] border border-slate-800 bg-[#08101e] p-4">
                <div class="mb-3 flex items-center justify-between">
                  <span class="font-mono text-[11px] uppercase tracking-[0.24em] text-slate-500">Tongyi Coach</span>
                  <span class="text-xs text-slate-400">SSE Stream</span>
                </div>
                <div class="min-h-28 whitespace-pre-wrap rounded-2xl border border-slate-800 bg-slate-950/90 p-4 font-mono text-sm leading-7 text-slate-100">
                  {{ consoleState.aiAdvice }}
                  <span v-if="aiLoading" class="ml-1 inline-block h-4 w-2 animate-pulse bg-cyan-300 align-middle"></span>
                </div>
              </section>
            </div>
          </div>
        </section>
      </section>
    </main>
  </div>
</template>
