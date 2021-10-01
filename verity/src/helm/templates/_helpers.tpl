{{/*
Expand the name of the chart.
*/}}
{{- define "verity.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
*/}}
{{- define "verity.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s-%s" .Values.service .Values.name .Values.env | trunc 63 | trimSuffix "-" -}}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "verity.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "verity.labels" -}}
helm.sh/chart: {{ include "verity.chart" . }}
{{ include "verity.selectorLabels" . }}
{{- if .Values.version }}
app.kubernetes.io/version: {{ .Values.version | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "verity.selectorLabels" -}}
app.kubernetes.io/name: {{ include "verity.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "verity.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "verity.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Vault injection template
*/}}
{{- define "verity.vaultInjectTemplate" -}}
{{`{{ with secret `}}{{ .Secret }}{{` -}}`}}
{{`{{ range $k, $v := .Data.data -}}
  export {{ $k }}={{ $v }}
{{ end }}
{{- end }}`}}
{{- end }}
