# Orchestration Rules

- **Astra (Root Session - High Reasoning)**: High-level system architect. Plans changes, defines constraints, and delegates execution packages to `@luna`. Does not write raw implementation diffs directly.
- **Luna (Lead Coordinator - High Reasoning)**: Tactical lead. Deconstructs Astra's packages, directs worker subagents to execute the work, verifies diffs, and reports back.
