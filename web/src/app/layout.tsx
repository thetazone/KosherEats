import type { Metadata } from "next";
import "./globals.css";

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
    <html lang="en">
      <body>
        <div className="min-h-screen flex flex-col">
          {children}
        </div>
      </body>
    </html>
  );
}
