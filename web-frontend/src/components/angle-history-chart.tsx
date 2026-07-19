"use client";

import {
  Area,
  CartesianGrid,
  ComposedChart,
  Line,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

import type { AngleSample } from "@/lib/contracts";
import styles from "./dashboard.module.css";

export function AngleHistoryChart({ history }: { history: AngleSample[] }) {
  if (!history.length) {
    return (
      <div className={styles.chartEmpty}>
        <span>SPATIAL TRACE / 00</span>
        <strong>No movement recorded</strong>
        <p>The trace begins with the first synchronized joint angle.</p>
      </div>
    );
  }

  return (
    <ResponsiveContainer width="100%" height="100%">
      <ComposedChart data={history} margin={{ top: 14, right: 10, bottom: 0, left: -18 }}>
        <defs>
          <linearGradient id="angleFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#8e84ff" stopOpacity={0.28} />
            <stop offset="100%" stopColor="#8e84ff" stopOpacity={0} />
          </linearGradient>
        </defs>
        <CartesianGrid vertical={false} stroke="rgba(238,234,226,.1)" strokeDasharray="2 7" />
        <XAxis
          dataKey="elapsedSeconds"
          tickFormatter={(value: number) => `${Math.round(value)}s`}
          tick={{ fill: "#7f788b", fontSize: 9, fontFamily: "var(--font-mono)" }}
          axisLine={false}
          tickLine={false}
          minTickGap={40}
        />
        <YAxis
          domain={[0, 180]}
          ticks={[0, 45, 90, 135, 180]}
          tick={{ fill: "#7f788b", fontSize: 9, fontFamily: "var(--font-mono)" }}
          axisLine={false}
          tickLine={false}
        />
        <Tooltip
          cursor={{ stroke: "rgba(115,239,208,.45)", strokeDasharray: "3 5" }}
          contentStyle={{
            background: "rgba(21,17,38,.96)",
            border: "1px solid rgba(238,234,226,.16)",
            borderRadius: 0,
            color: "#eeeae2",
            fontFamily: "var(--font-mono)",
            fontSize: 10,
          }}
          formatter={(value) => [`${Number(value).toFixed(1)}°`, "Primary angle"]}
          labelFormatter={(value) => `${Number(value).toFixed(1)} seconds`}
        />
        <ReferenceLine y={90} stroke="#ff745d" strokeDasharray="4 8" opacity={0.55} />
        <Area type="monotone" dataKey="angle" fill="url(#angleFill)" stroke="none" isAnimationActive={false} />
        <Line
          type="monotone"
          dataKey="angle"
          stroke="#73efd0"
          strokeWidth={2.4}
          dot={false}
          activeDot={{ r: 4, fill: "#ff745d", stroke: "#eeeae2", strokeWidth: 2 }}
          isAnimationActive={false}
        />
      </ComposedChart>
    </ResponsiveContainer>
  );
}
