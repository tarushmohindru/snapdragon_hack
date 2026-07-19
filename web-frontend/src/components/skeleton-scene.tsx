"use client";

import {
  AdaptiveDpr,
  Grid,
  Line,
  OrbitControls,
  PerspectiveCamera,
  Preload,
} from "@react-three/drei";
import { Canvas } from "@react-three/fiber";
import { Bloom, EffectComposer, Vignette } from "@react-three/postprocessing";
import { useMemo } from "react";
import type { Vector3Tuple } from "three";
import type { Joint3D } from "@/lib/contracts";

const EDGES = [
  [0, 1], [0, 2], [1, 3], [2, 4],
  [5, 6], [5, 7], [7, 9], [6, 8], [8, 10],
  [5, 11], [6, 12], [11, 12],
  [11, 13], [13, 15], [12, 14], [14, 16],
] as const;

function normalizedJoints(joints: Record<string, Joint3D>) {
  const entries = Object.entries(joints).map(([id, point]) => [
    id,
    [point.x, point.y, point.z] satisfies Vector3Tuple,
  ] as const);
  if (!entries.length) return {};
  const xs = entries.map(([, point]) => point[0]);
  const ys = entries.map(([, point]) => point[1]);
  const zs = entries.map(([, point]) => point[2]);
  const center: Vector3Tuple = [
    (Math.min(...xs) + Math.max(...xs)) / 2,
    Math.min(...ys),
    (Math.min(...zs) + Math.max(...zs)) / 2,
  ];
  const height = Math.max(Math.max(...ys) - Math.min(...ys), 0.001);
  const scale = 3.55 / height;
  return Object.fromEntries(
    entries.map(([id, point]) => [
      id,
      [
        (point[0] - center[0]) * scale,
        (point[1] - center[1]) * scale,
        (point[2] - center[2]) * scale,
      ] satisfies Vector3Tuple,
    ]),
  );
}

function MeasurementRig() {
  return (
    <group position={[0, 0.08, 0]} rotation={[Math.PI / 2, 0, 0]}>
      {[1.65, 2.15, 2.7].map((radius, index) => (
        <mesh key={radius} rotation={[0, 0, index * 0.32]}>
          <torusGeometry args={[radius, index === 0 ? 0.008 : 0.004, 6, 128]} />
          <meshBasicMaterial
            color={index === 0 ? "#8e84ff" : "#4d456a"}
            transparent
            opacity={index === 0 ? 0.5 : 0.36}
          />
        </mesh>
      ))}
    </group>
  );
}

function Skeleton({ joints }: { joints: Record<string, Joint3D> }) {
  const points = useMemo(() => normalizedJoints(joints), [joints]);
  return (
    <group position={[0, -1.78, 0]}>
      {EDGES.map(([start, end]) => {
        const from = points[String(start)];
        const to = points[String(end)];
        if (!from || !to) return null;
        return (
          <Line
            key={`${start}-${end}`}
            points={[from, to]}
            color="#73efd0"
            lineWidth={4.5}
            transparent
            opacity={0.95}
          />
        );
      })}
      {Object.entries(points).map(([id, position]) => (
        <group key={id} position={position}>
          <mesh>
            <sphereGeometry args={[Number(id) <= 4 ? 0.052 : 0.068, 24, 24]} />
            <meshStandardMaterial
              color="#fff4e8"
              emissive="#ff745d"
              emissiveIntensity={2.8}
              roughness={0.22}
              metalness={0.16}
            />
          </mesh>
          <mesh>
            <sphereGeometry args={[Number(id) <= 4 ? 0.078 : 0.1, 20, 20]} />
            <meshBasicMaterial color="#ff745d" transparent opacity={0.1} />
          </mesh>
        </group>
      ))}
    </group>
  );
}

export default function SkeletonScene({
  joints,
}: {
  joints: Record<string, Joint3D>;
}) {
  return (
    <Canvas
      dpr={[1, 1.75]}
      gl={{ antialias: true, alpha: true, powerPreference: "high-performance" }}
    >
      <fog attach="fog" args={["#0f0c1c", 6.5, 12]} />
      <PerspectiveCamera makeDefault position={[4.2, 2.25, 5.6]} fov={36} />
      <ambientLight intensity={0.75} />
      <directionalLight position={[3, 7, 4]} intensity={3.8} color="#fff4e8" />
      <directionalLight position={[-4, 2, -3]} intensity={3.2} color="#8e84ff" />
      <pointLight position={[0, 1.5, 2.5]} intensity={14} distance={6} color="#73efd0" />

      <MeasurementRig />
      <Skeleton joints={joints} />

      <Grid
        position={[0, -1.8, 0]}
        args={[9, 9]}
        cellSize={0.35}
        cellThickness={0.45}
        cellColor="#342d4c"
        sectionSize={1.4}
        sectionThickness={0.8}
        sectionColor="#655a8b"
        fadeDistance={8}
        fadeStrength={1.6}
        infiniteGrid
      />

      <OrbitControls
        makeDefault
        enablePan={false}
        minDistance={4.2}
        maxDistance={8.5}
        minPolarAngle={Math.PI / 4.2}
        maxPolarAngle={Math.PI / 2.03}
        dampingFactor={0.065}
        enableDamping
      />

      <EffectComposer multisampling={0}>
        <Bloom luminanceThreshold={0.42} luminanceSmoothing={0.7} intensity={1.05} mipmapBlur />
        <Vignette eskil={false} offset={0.18} darkness={0.65} />
      </EffectComposer>
      <AdaptiveDpr pixelated />
      <Preload all />
    </Canvas>
  );
}
