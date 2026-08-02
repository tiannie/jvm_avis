{{/*
Expand the name of the chart.
*/}}
{{- define "jvm-avis-demo.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Fully qualified name for the demo-target workload.
*/}}
{{- define "jvm-avis-demo.demoFullname" -}}
{{- if .Values.demo.fullnameOverride }}
{{- .Values.demo.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-demo-target" .Release.Name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{- define "jvm-avis-demo.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "jvm-avis-demo.labels" -}}
helm.sh/chart: {{ include "jvm-avis-demo.chart" . }}
{{ include "jvm-avis-demo.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "jvm-avis-demo.selectorLabels" -}}
app.kubernetes.io/name: {{ include "jvm-avis-demo.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: demo-target
{{- end }}
