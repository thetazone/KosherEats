import type { Metadata } from "next";
import { Inter } from "next/font/google";
import "./globals.css";

const inter = Inter({
  subsets: ["latin"],
  display: "swap",
  variable: "--font-inter",
});

export const metadata: Metadata = {
  title: "KosherEats — Kosher Food, Delivered",
  description:
    "Order from verified kosher-certified restaurants near you. Filter by OU, OK, Star-K, Kof-K, cRc, Badatz, Chof-K. Glatt Kosher, Cholov Yisroel, Pas Yisroel. Download on iOS and Android.",
  keywords: [
    "kosher food delivery",
    "kosher restaurant delivery",
    "glatt kosher",
    "cholov yisroel",
    "kosher near me",
    "kosher food order",
    "koshereats",
  ],
  openGraph: {
    title: "KosherEats — Kosher Food, Delivered",
    description:
      "Order from verified kosher-certified restaurants near you. Every restaurant verified. Every meal trusted.",
    url: "https://koshereats.shop",
    siteName: "KosherEats",
    type: "website",
  },
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" className={inter.variable}>
      <body>
        <div className="min-h-screen flex flex-col">
          {children}
        </div>
      </body>
    </html>
  );
}
