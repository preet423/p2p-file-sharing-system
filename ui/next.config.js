/** @type {import('next').NextConfig} */
const BACKEND = process.env.BACKEND_URL || 'http://localhost:8080';
const nextConfig = {
  reactStrictMode: true,
  swcMinify: true,
  async rewrites() {
    return [
      {
        source: '/api/upload',
        destination: `${BACKEND}/upload`,
      },
      {
        source: '/api/download/:fileId',
        destination: `${BACKEND}/download/:fileId`,
      },
    ];
  },
}

module.exports = nextConfig
