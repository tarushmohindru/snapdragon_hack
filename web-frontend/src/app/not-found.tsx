import Link from "next/link";

export default function NotFound() {
  return (
    <main style={{ minHeight: "100vh", display: "grid", placeItems: "center", padding: 24, textAlign: "center" }}>
      <section>
        <p style={{ color: "var(--indigo)", fontFamily: "var(--font-mono)", letterSpacing: ".12em" }}>404 / NO SIGNAL</p>
        <h1 style={{ fontFamily: "var(--font-display)", fontSize: 48, textTransform: "uppercase", margin: "8px 0" }}>Dashboard not found</h1>
        <Link href="/" style={{ color: "var(--indigo)", fontWeight: 800 }}>Return to live biomechanics</Link>
      </section>
    </main>
  );
}
