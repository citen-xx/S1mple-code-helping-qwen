/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        panel: '#fff7ef',
        line: '#ead1b9',
        glow: '#f6b94a',
        cyan: '#d27a3b',
        pink: '#d96f4d',
        ink: '#3d2a1f',
        muted: '#907868'
      },
      boxShadow: {
        neon: '0 0 0 1px rgba(190, 108, 45, 0.12), 0 28px 70px rgba(120, 58, 22, 0.18)',
        soft: '0 16px 45px rgba(110, 61, 28, 0.12)',
        warm: '0 24px 60px rgba(113, 58, 21, 0.22)'
      },
      fontFamily: {
        display: ['"Segoe UI"', '"PingFang SC"', '"Microsoft YaHei"', 'sans-serif'],
        mono: ['"Cascadia Code"', 'Consolas', '"JetBrains Mono"', 'monospace']
      },
      backgroundImage: {
        grid: 'linear-gradient(rgba(185, 112, 55, 0.08) 1px, transparent 1px), linear-gradient(90deg, rgba(185, 112, 55, 0.08) 1px, transparent 1px)'
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
