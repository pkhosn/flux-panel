import { Button } from "@heroui/button";
import { useTheme } from "@heroui/use-theme";
import { applyTheme, saveTheme, type ThemeMode } from "@/utils/theme";

interface ThemeToggleProps {
  iconOnly?: boolean;
  size?: "sm" | "md" | "lg";
}

export function ThemeToggle({ iconOnly = true, size = "sm" }: ThemeToggleProps) {
  const { theme, setTheme } = useTheme();
  const currentTheme: ThemeMode = theme === "dark" ? "dark" : "light";
  const nextTheme: ThemeMode = currentTheme === "dark" ? "light" : "dark";

  const handleToggle = () => {
    saveTheme(nextTheme);
    applyTheme(nextTheme);
    setTheme(nextTheme);
  };

  return (
    <Button
      isIconOnly={iconOnly}
      variant="light"
      size={size}
      onPress={handleToggle}
      title={currentTheme === "dark" ? "切换到白天模式" : "切换到黑暗模式"}
      aria-label={currentTheme === "dark" ? "切换到白天模式" : "切换到黑暗模式"}
    >
      {currentTheme === "dark" ? (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path d="M10 2a1 1 0 011 1v1a1 1 0 11-2 0V3a1 1 0 011-1zm0 12a4 4 0 100-8 4 4 0 000 8zm7-5a1 1 0 100-2h-1a1 1 0 100 2h1zM5 10a1 1 0 100-2H4a1 1 0 100 2h1zm9.657 4.243a1 1 0 10-1.414 1.414l.707.707a1 1 0 001.414-1.414l-.707-.707zm-8.485-8.486a1 1 0 10-1.414 1.414l.707.707A1 1 0 106.88 6.464l-.707-.707zm8.485 0l.707-.707a1 1 0 00-1.414-1.414l-.707.707a1 1 0 101.414 1.414zM6.88 13.536a1 1 0 10-1.414-1.414l-.707.707a1 1 0 001.414 1.414l.707-.707z" />
        </svg>
      ) : (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path d="M17.293 13.293A8 8 0 016.707 2.707a8.001 8.001 0 1010.586 10.586z" />
        </svg>
      )}
    </Button>
  );
}

