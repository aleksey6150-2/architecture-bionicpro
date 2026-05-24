import React, { useEffect, useState } from 'react';

interface UserInfo {
  username: string;
  email: string;
  name: string;
  roles: string[];
}

const BFF_URL = process.env.REACT_APP_BFF_URL || 'http://localhost:9000';

const ReportPage: React.FC = () => {
  const [user, setUser] = useState<UserInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [reportLoading, setReportLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetch(`${BFF_URL}/me`, { credentials: 'include' })
      .then(async (res) => {
        if (res.status === 401) {
          setUser(null);
          return;
        }
        if (!res.ok) {
          throw new Error(`/me failed: ${res.status}`);
        }
        setUser(await res.json());
      })
      .catch((err) => setError(err instanceof Error ? err.message : String(err)))
      .finally(() => setLoading(false));
  }, []);

  const login = () => {
    window.location.href = `${BFF_URL}/oauth2/authorization/keycloak`;
  };

  const logout = () => {
    window.location.href = `${BFF_URL}/logout`;
  };

  const downloadReport = async () => {
    setReportLoading(true);
    setError(null);
    try {
      // Шаг 1. Берём CDN-ссылку у reports-api (через BFF).
      // reports-api: либо берёт готовый отчёт из S3, либо генерит и кладёт туда.
      const metaResp = await fetch(`${BFF_URL}/api/reports`, {
        credentials: 'include',
      });
      if (metaResp.status === 401) {
        setUser(null);
        return;
      }
      if (!metaResp.ok) {
        throw new Error(`Report metadata failed: ${metaResp.status}`);
      }
      const meta = await metaResp.json();
      if (!meta.report_url) {
        throw new Error(meta.note || 'Report not available yet');
      }

      // Шаг 2. Качаем сам JSON с CDN (Nginx → Minio).
      // Cookie сюда не нужен — bucket с публичным read через CDN.
      const blob = await fetch(meta.report_url).then((r) => {
        if (!r.ok) throw new Error(`CDN fetch failed: ${r.status}`);
        return r.blob();
      });

      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `report-${meta.period_to}.json`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setReportLoading(false);
    }
  };

  if (loading) {
    return <div className="flex items-center justify-center min-h-screen">Loading...</div>;
  }

  if (!user) {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen bg-gray-100">
        <button
          onClick={login}
          className="px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600"
        >
          Login
        </button>
        {error && (
          <div className="mt-4 p-4 bg-red-100 text-red-700 rounded">
            {error}
          </div>
        )}
      </div>
    );
  }

  return (
    <div className="flex flex-col items-center justify-center min-h-screen bg-gray-100">
      <div className="p-8 bg-white rounded-lg shadow-md max-w-md">
        <div className="mb-4 text-sm text-gray-600">
          <div>Welcome, <span className="font-medium">{user.name || user.username}</span></div>
          {user.email && <div>{user.email}</div>}
          {user.roles.length > 0 && (
            <div>Roles: {user.roles.join(', ')}</div>
          )}
        </div>

        <h1 className="text-2xl font-bold mb-6">Usage Reports</h1>

        <div className="flex gap-2">
          <button
            onClick={downloadReport}
            disabled={reportLoading}
            className={`px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600 ${
              reportLoading ? 'opacity-50 cursor-not-allowed' : ''
            }`}
          >
            {reportLoading ? 'Generating Report...' : 'Download Report'}
          </button>

          <button
            onClick={logout}
            className="px-4 py-2 bg-gray-300 text-gray-800 rounded hover:bg-gray-400"
          >
            Logout
          </button>
        </div>

        {error && (
          <div className="mt-4 p-4 bg-red-100 text-red-700 rounded">
            {error}
          </div>
        )}
      </div>
    </div>
  );
};

export default ReportPage;
