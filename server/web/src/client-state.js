/** Keep the full directory for historical labels; filter only a specific view or choice. */
export const activeClients=clients=>clients.filter(client=>!client.retired);
export const clientChoices=(clients,retained=[])=>clients.filter(client=>!client.retired||retained.includes(client.id));
export const projectChoices=(projects,clients,retained=[])=>projects.filter(project=>retained.includes(project.id)||!clients.find(client=>client.id===project.clientId)?.retired);
