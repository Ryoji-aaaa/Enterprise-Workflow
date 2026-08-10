import Image from "next/image";

export function WorkflowLogo({ className }: { className: string }) {
  return (
    <Image
      alt=""
      aria-hidden="true"
      className={className}
      height={44}
      src="/favicon.ico"
      unoptimized
      width={44}
    />
  );
}
