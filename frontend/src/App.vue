<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ensureMonacoEnvironment } from './monacoEnv'

const apiBase = import.meta.env.VITE_API_BASE ?? ''
const editorRef = ref(null)
const consoleRef = ref(null)

const questionId = ref(Number(new URLSearchParams(window.location.search).get('id') || 1))
const questionLoading = ref(false)
const questionError = ref('')
const questionSummary = ref('')
const runLoading = ref(false)
const aiLoading = ref(false)
const language = ref('java')
const availableQuestions = ref([])

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
    return 'border-emerald-700/20 bg-emerald-100/70 text-emerald-900'
  }
  if (difficulty.includes('hard') || difficulty.includes('困难')) {
    return 'border-rose-700/20 bg-rose-100/70 text-rose-900'
  }
  return 'border-amber-700/20 bg-amber-100/70 text-amber-900'
})

const statusTone = computed(() => {
  const status = consoleState.status.toLowerCase()
  if (status.includes('accepted')) {
    return 'border-emerald-700/20 bg-emerald-100/80 text-emerald-900'
  }
  if (status.includes('wrong') || status.includes('error') || status.includes('failed')) {
    return 'border-rose-700/20 bg-rose-100/80 text-rose-900'
  }
  if (status.includes('running') || status.includes('idle')) {
    return 'border-amber-700/20 bg-amber-100/80 text-amber-900'
  }
  return 'border-orange-700/20 bg-orange-100/80 text-orange-900'
})

const remoteLabel = computed(() => apiBase || 'Vite /api proxy')
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

function resetQuestionDetail() {
  questionDetail.question = {
    id: null,
    title: '暂无题目',
    content: '',
    difficulty: '',
    timeLimit: 1000,
    memoryLimit: 128
  }
  questionDetail.testCases = []
}

async function parseResponseJson(response) {
  const text = await response.text()
  if (!text) {
    throw new Error('服务端返回空响应')
  }

  try {
    return JSON.parse(text)
  } catch {
    throw new Error(text.length < 180 ? text : '服务端返回了非 JSON 响应')
  }
}

async function initEditor() {
  if (!editorRef.value) {
    return
  }

  ensureMonacoEnvironment()
  monaco = await import('monaco-editor')

  monaco.editor.defineTheme('sunbaked-code', {
    base: 'vs-dark',
    inherit: true,
    rules: [
      { token: 'keyword', foreground: 'F59E0B' },
      { token: 'string', foreground: 'FCD34D' },
      { token: 'number', foreground: 'FB7185' },
      { token: 'comment', foreground: 'A88B72' }
    ],
    colors: {
      'editor.background': '#2B1D18',
      'editorLineNumber.foreground': '#8E6D5A',
      'editorCursor.foreground': '#FCD34D',
      'editor.selectionBackground': '#5B3428',
      'editor.lineHighlightBackground': '#3A241D'
    }
  })

  editor = monaco.editor.create(editorRef.value, {
    value: codeTemplates[language.value],
    language: 'java',
    theme: 'sunbaked-code',
    automaticLayout: true,
    fontFamily: 'Cascadia Code, Consolas, JetBrains Mono, monospace',
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

async function fetchQuestionList() {
  try {
    const response = await fetch(buildApiUrl('/api/questions'))
    const result = await parseResponseJson(response)

    if (!response.ok || result.code !== 0 || !Array.isArray(result.data)) {
      throw new Error(result.message || '题目列表获取失败')
    }

    availableQuestions.value = result.data

    if (result.data.length === 0) {
      questionSummary.value = '远程服务器当前没有题目数据，请先在后端创建题目。'
      pushLog('error', 'Question', 'Remote server has no question data')
      return false
    }

    questionSummary.value = `远程服务器共有 ${result.data.length} 道题`

    const exists = result.data.some((item) => item.id === questionId.value)
    if (!exists) {
      questionId.value = result.data[0].id
      pushLog('system', 'Question', `Question #${questionId.value} selected automatically`)
    }

    return true
  } catch (error) {
    questionSummary.value = error.message || '题目列表获取失败'
    pushLog('error', 'Question', questionSummary.value)
    return false
  }
}

async function loadQuestion() {
  questionLoading.value = true
  questionError.value = ''

  try {
    const hasQuestions = await fetchQuestionList()
    if (!hasQuestions) {
      resetQuestionDetail()
      questionError.value = questionSummary.value
      return
    }

    const response = await fetch(buildApiUrl(`/api/questions/${questionId.value}/detail`))
    const result = await parseResponseJson(response)

    if (!response.ok || result.code !== 0 || !result.data) {
      throw new Error(result.message || '题目加载失败')
    }

    questionDetail.question = result.data.question
    questionDetail.testCases = result.data.testCases || []
    questionSummary.value = `已连接 ${remoteLabel.value}`
    pushLog('success', 'Question', `Loaded question #${questionId.value}`)
  } catch (error) {
    resetQuestionDetail()
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
    const result = await parseResponseJson(response)

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
  <div class="warm-stage relative min-h-screen overflow-hidden">
    <div class="pointer-events-none absolute inset-0 warm-pattern opacity-80"></div>
    <div class="pointer-events-none absolute -left-24 top-16 h-72 w-72 rounded-full bg-orange-200/55 blur-3xl"></div>
    <div class="pointer-events-none absolute right-[-6rem] top-10 h-80 w-80 rounded-full bg-amber-300/45 blur-3xl"></div>
    <div class="pointer-events-none absolute bottom-[-5rem] left-1/3 h-72 w-72 rounded-full bg-rose-200/35 blur-3xl"></div>

    <main class="relative mx-auto flex min-h-screen max-w-[1800px] flex-col px-4 py-5 lg:px-6 xl:h-screen xl:overflow-hidden">
      <header class="warm-card relative mb-5 shrink-0 overflow-hidden rounded-[32px] border border-orange-950/10 px-5 py-5 shadow-warm glass">
        <div class="warm-radial pointer-events-none absolute right-0 top-0 h-full w-1/2 opacity-60"></div>
        <div class="relative flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
          <div class="max-w-3xl">
            <p class="font-mono text-xs uppercase tracking-[0.35em] text-orange-900/70">Simple AI OJ</p>
            <h1 class="mt-3 text-4xl font-bold text-stone-900 lg:text-5xl">暖光刷题台</h1>
            <p class="mt-3 max-w-2xl text-sm leading-7 text-stone-700">
              把原来的冷色控制台改成了更柔和的暖色界面。题面区像纸张卡片，代码区像深色烘焙工作台，控制台则偏焦糖色，整体更适合长时间阅读和写题。
            </p>
            <div class="mt-4 flex flex-wrap items-center gap-3">
              <span class="rounded-full border border-orange-950/10 bg-white/55 px-3 py-1 font-mono text-[11px] uppercase tracking-[0.25em] text-stone-700">
                Backend: {{ remoteLabel }}
              </span>
              <span v-if="questionSummary" class="rounded-full border border-orange-950/10 bg-amber-50/80 px-3 py-1 text-xs text-stone-700">
                {{ questionSummary }}
              </span>
            </div>
          </div>

          <div class="flex flex-col gap-3 sm:flex-row sm:items-center">
            <label class="warm-input flex items-center gap-3 rounded-[22px] px-4 py-3">
              <span class="font-mono text-xs uppercase tracking-[0.2em] text-stone-500">Question ID</span>
              <input
                v-model.number="questionId"
                type="number"
                min="1"
                class="w-24 bg-transparent font-mono text-sm text-stone-900 outline-none"
              />
            </label>

            <button
              class="warm-button-primary rounded-[22px] px-5 py-3 text-sm font-semibold transition"
              :disabled="questionLoading"
              @click="loadQuestion"
            >
              {{ questionLoading ? '载入中...' : '载入题目' }}
            </button>
          </div>
        </div>
      </header>

      <section class="grid flex-1 min-h-0 gap-5 xl:grid-cols-[minmax(360px,0.92fr)_minmax(560px,1.4fr)]">
        <aside class="warm-card rounded-[30px] border border-orange-950/10 p-5 shadow-soft xl:min-h-0 xl:flex xl:flex-col xl:overflow-hidden">
          <div class="shrink-0">
            <div class="mb-4 flex flex-wrap items-center gap-3">
              <span class="warm-chip bg-white/70 text-stone-700">#{{ questionDetail.question.id ?? '--' }}</span>
              <span class="warm-chip" :class="difficultyTone">
                {{ questionDetail.question.difficulty || 'Unknown' }}
              </span>
              <span class="warm-chip bg-white/70 text-stone-700">{{ questionDetail.question.timeLimit }} ms</span>
              <span class="warm-chip bg-white/70 text-stone-700">{{ questionDetail.question.memoryLimit }} MB</span>
            </div>

            <div class="paper-card rounded-[26px] p-5">
              <div class="mb-4 flex items-start justify-between gap-4">
                <div>
                  <h2 class="text-3xl font-bold text-stone-900">{{ questionDetail.question.title }}</h2>
                  <p class="mt-2 text-sm text-stone-500">Read slowly, solve steadily.</p>
                </div>
                <div class="stamp-badge hidden rounded-full px-3 py-2 text-[11px] font-bold uppercase tracking-[0.25em] text-orange-900 md:block">
                  Problem
                </div>
              </div>

              <p v-if="questionError" class="mt-4 rounded-2xl border border-rose-300 bg-rose-50 px-4 py-3 text-sm text-rose-900">
                {{ questionError }}
              </p>

              <div class="mt-4 whitespace-pre-wrap text-sm leading-7 text-stone-700">
                {{ questionDetail.question.content || '暂无题面内容。' }}
              </div>
            </div>
          </div>

          <div class="mt-5 min-h-0 flex-1 overflow-y-auto pr-1">
            <div class="mb-3 flex items-center justify-between">
              <h3 class="text-lg font-semibold text-stone-900">测试用例</h3>
              <span class="font-mono text-xs uppercase tracking-[0.24em] text-stone-500">
                {{ questionDetail.testCases.length }} cases
              </span>
            </div>

            <div class="space-y-3">
              <article
                v-for="testCase in questionDetail.testCases"
                :key="testCase.id"
                class="rounded-[24px] border border-orange-950/10 bg-white/55 p-4 shadow-sm"
              >
                <div class="mb-3 flex items-center justify-between">
                  <span class="font-mono text-xs uppercase tracking-[0.24em] text-orange-900/80">Case {{ testCase.id }}</span>
                </div>
                <div class="grid gap-3 md:grid-cols-2">
                  <div class="rounded-2xl border border-orange-950/10 bg-[#fff7ef] p-3">
                    <div class="mb-2 font-mono text-[11px] uppercase tracking-[0.22em] text-stone-500">Input</div>
                    <pre class="whitespace-pre-wrap font-mono text-sm text-stone-800">{{ testCase.input }}</pre>
                  </div>
                  <div class="rounded-2xl border border-orange-950/10 bg-[#fff7ef] p-3">
                    <div class="mb-2 font-mono text-[11px] uppercase tracking-[0.22em] text-stone-500">Expected</div>
                    <pre class="whitespace-pre-wrap font-mono text-sm text-stone-800">{{ testCase.expectedOutput }}</pre>
                  </div>
                </div>
              </article>
            </div>
          </div>
        </aside>

        <section class="grid min-h-[780px] min-w-0 gap-5 xl:min-h-0 xl:grid-rows-[minmax(0,1fr)_minmax(320px,0.84fr)]">
          <div class="editor-shell min-h-[420px] min-w-0 rounded-[30px] border border-orange-950/10 p-4 shadow-warm xl:min-h-0 xl:flex xl:flex-col">
            <div class="mb-4 flex shrink-0 flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
              <div>
                <p class="font-mono text-xs uppercase tracking-[0.3em] text-amber-200/70">Code Arena</p>
                <h3 class="mt-2 text-2xl font-semibold text-amber-50">Monaco Editor</h3>
              </div>

              <div class="flex flex-wrap items-center gap-3">
                <select
                  v-model="language"
                  class="rounded-[20px] border border-amber-100/15 bg-[#3b241c] px-4 py-3 font-mono text-sm text-amber-50 outline-none"
                >
                  <option value="java">Java</option>
                  <option value="cpp">C++</option>
                </select>

                <button
                  class="warm-button-success rounded-[20px] px-5 py-3 text-sm font-semibold transition"
                  :disabled="runLoading"
                  @click="runCode"
                >
                  {{ runLoading ? '评测中...' : '运行评测' }}
                </button>

                <button
                  class="warm-button-accent rounded-[20px] px-5 py-3 text-sm font-semibold transition"
                  :disabled="aiLoading"
                  @click="askAiCoach"
                >
                  {{ aiLoading ? '求助中...' : '求助通义千问' }}
                </button>
              </div>
            </div>

            <div class="mb-4 h-1.5 shrink-0 overflow-hidden rounded-full bg-black/20">
              <div class="h-full w-full origin-left rounded-full bg-gradient-to-r from-amber-300 via-orange-300 to-rose-300 animate-pulsebar"></div>
            </div>

            <div
              ref="editorRef"
              class="min-h-[340px] min-w-0 flex-1 overflow-hidden rounded-[24px] border border-amber-100/10 xl:min-h-0"
            ></div>
          </div>

          <div class="console-shell min-h-[320px] rounded-[30px] border border-orange-950/10 p-4 shadow-soft xl:min-h-0 xl:flex xl:flex-col">
            <div class="mb-4 shrink-0 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
              <div>
                <p class="font-mono text-xs uppercase tracking-[0.3em] text-stone-500">Console Matrix</p>
                <h3 class="mt-2 text-2xl font-semibold text-stone-900">运行结果与 AI 辅导</h3>
              </div>

              <div class="flex flex-wrap items-center gap-3">
                <span class="warm-chip" :class="statusTone">
                  {{ consoleState.status }}
                </span>
                <span class="warm-chip bg-white/70 text-stone-600">
                  {{ aiLoading ? 'AI Streaming' : 'AI Standby' }}
                </span>
              </div>
            </div>

            <div ref="consoleRef" class="grid min-h-[240px] flex-1 gap-4 overflow-y-auto pr-1 lg:grid-cols-[0.9fr_1.1fr] xl:min-h-0">
              <section class="rounded-[24px] border border-orange-950/10 bg-[#fff7ef] p-4">
                <div class="mb-3 flex items-center justify-between">
                  <span class="font-mono text-[11px] uppercase tracking-[0.24em] text-stone-500">Judge Output</span>
                  <span class="text-xs text-stone-500">{{ consoleState.message }}</span>
                </div>
                <pre class="min-h-28 whitespace-pre-wrap rounded-2xl border border-orange-950/10 bg-white/75 p-4 font-mono text-sm leading-6 text-stone-800">{{ consoleState.output || '暂无输出' }}</pre>

                <div class="mt-4 space-y-2">
                  <article
                    v-for="item in consoleState.logs"
                    :key="item.id"
                    class="rounded-2xl border px-3 py-2"
                    :class="{
                      'border-amber-300/70 bg-amber-100/60': item.type === 'system',
                      'border-emerald-300/70 bg-emerald-100/70': item.type === 'success',
                      'border-rose-300/70 bg-rose-100/70': item.type === 'error'
                    }"
                  >
                    <div class="font-mono text-[11px] uppercase tracking-[0.22em] text-stone-500">{{ item.title }}</div>
                    <p class="mt-1 text-sm text-stone-800">{{ item.content }}</p>
                  </article>
                </div>
              </section>

              <section class="rounded-[24px] border border-orange-950/10 bg-[#fff7ef] p-4">
                <div class="mb-3 flex items-center justify-between">
                  <span class="font-mono text-[11px] uppercase tracking-[0.24em] text-stone-500">Tongyi Coach</span>
                  <span class="text-xs text-stone-500">SSE Stream</span>
                </div>
                <div class="paper-card min-h-28 whitespace-pre-wrap rounded-2xl p-4 font-mono text-sm leading-7 text-stone-800">
                  {{ consoleState.aiAdvice }}
                  <span v-if="aiLoading" class="ml-1 inline-block h-4 w-2 animate-pulse bg-orange-500 align-middle"></span>
                </div>
              </section>
            </div>
          </div>
        </section>
      </section>
    </main>
  </div>
</template>
