/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        panel: '#0d1321',
        line: '#1b2440',
        glow: '#4df5c6',
        cyan: '#55c8ff',
        pink: '#ff4fd8',
        ink: '#d7e2ff',
        muted: '#7f8aac'
      },
      boxShadow: {
        neon: '0 0 0 1px rgba(85, 200, 255, 0.2), 0 24px 80px rgba(0, 0, 0, 0.45)',
        soft: '0 18px 50px rgba(0, 0, 0, 0.25)'
      },
      fontFamily: {
        display: ['"Segoe UI"', '"PingFang SC"', '"Microsoft YaHei"', 'sans-serif'],
        mono: ['"Cascadia Code"', 'Consolas', '"JetBrains Mono"', 'monospace']
      },
      backgroundImage: {
        grid: 'linear-gradient(rgba(85, 200, 255, 0.08) 1px, transparent 1px), linear-gradient(90deg, rgba(85, 200, 255, 0.08) 1px, transparent 1px)'
      },
      animation: {
        pulsebar: 'pulsebar 2.4s ease-in-out infinite'
      },
      keyframes: {
        pulsebar: {
          '0%, 100%': { opacity: '0.45', transform: 'scaleX(0.98)' },
          '50%': { opacity: '1', transform: 'scaleX(1)' }
        }
      }
    }
  },
  plugins: []
}
