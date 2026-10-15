import { Select as HeroSelect, type SelectProps } from '@heroui/select';
export { SelectItem, SelectSection } from '@heroui/select';

/** Modal autofocus can scroll the trigger while portaled options are mounting.
 * Disable the spring animation to avoid opacity restarting during overlay updates.
 * Keep the menu open during that adjustment; selection, Escape and outside click still dismiss it. */
export function Select<T extends object>(props: SelectProps<T>) {
  return <HeroSelect {...props} popoverProps={{ disableAnimation: true, shouldCloseOnBlur: false, shouldCloseOnScroll: false, ...props.popoverProps }} />;
}
