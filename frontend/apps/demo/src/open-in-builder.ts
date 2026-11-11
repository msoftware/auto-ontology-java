// SPDX-License-Identifier: Apache-2.0
import { useExploreStore } from '@jentsch/quasar-dashboard'

/**
 * FR-NLC-17: init({datasetId, vizType}), then setControl for every given param, then run()
 * (chart-builder.md, "Zustand programmatisch steuern").
 */
export async function openInBuilder(p: { datasetId: number; vizType: string; params: Record<string, unknown> }) {
  const explore = useExploreStore()
  await explore.init({ datasetId: p.datasetId, vizType: p.vizType as never })
  for (const [name, value] of Object.entries(p.params)) {
    if (name !== 'viz_type' && name !== 'datasource') explore.setControl(name, value)
  }
  await explore.run()
}
