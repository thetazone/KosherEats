// DietaryBadge is the item-level meat/dairy/pareve pill. One component owns
// the class map so the kosher triad renders identically on the consumer
// restaurant page, the add-to-cart modal, and the seller menu row
// (rubric dim 3: the triad must be consistent everywhere).
export type DietaryKind = "meat" | "dairy" | "pareve";

const DIETARY_BADGE: Record<DietaryKind, { label: string; className: string }> = {
  meat: { label: "Meat", className: "bg-meat-900/40 text-meat-400" },
  dairy: { label: "Dairy", className: "bg-dairy-900/40 text-dairy-400" },
  pareve: { label: "Pareve", className: "bg-pareve-900/40 text-pareve-400" },
};

export function dietaryKind(item: {
  is_meat?: boolean;
  is_dairy?: boolean;
  is_pareve?: boolean;
}): DietaryKind | null {
  if (item.is_meat) return "meat";
  if (item.is_dairy) return "dairy";
  if (item.is_pareve) return "pareve";
  return null;
}

export function DietaryBadge({ kind }: { kind: DietaryKind }) {
  const { label, className } = DIETARY_BADGE[kind];
  return (
    <span className={`text-xs font-medium px-2 py-0.5 rounded-full ${className}`}>
      {label}
    </span>
  );
}
