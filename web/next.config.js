/** @type {import('next').NextConfig} */
const nextConfig = {
  // 'standalone' output produces a self-contained server.js that copies
  // only the minimal deps. Used by the Dockerfile to keep the prod image
  // small (~100MB vs ~500MB without it).
  output: 'standalone',
  images: {
    // Unsplash is used for restaurant + menu item photos in dev.
    // Add the prod CDN host here when cutover happens.
    remotePatterns: [
      { protocol: 'https', hostname: 'images.unsplash.com' },
      { protocol: 'https', hostname: '**.s3.amazonaws.com' },
      { protocol: 'https', hostname: '**.cloudfront.net' },
    ],
  },
};

module.exports = nextConfig;
