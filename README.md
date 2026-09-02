# 体育商品进销管理系统

面向单门店、单仓库的体育商品进销管理软件。系统包含大类/小类与品牌、SPU/SKU、扫码入库、库存余额、移动平均成本、销售退货、经营报表、入库历史和库存流水；应用不提供登录功能，也不包含供应商模块。

商品大类和小类均由管理员手动填写两位数字编号。大类编号全局唯一，小类编号只需在所属大类内唯一。商品条码必须是至少三位的数字，前两位必须等于所属大类编号。入库时先扫描或手动输入条码，系统自动识别大类；未知商品再从该大类下选择小类进行快速建档。没有条码的商品也由管理员手动输入一个符合上述规则的独立编号。

## 环境要求

- Java 21（项目不支持用其他主版本代替）
- Maven 3.9 或更高版本
- Node.js 24
- npm 11 或与 Node.js 24 配套的版本

先检查环境：

```powershell
java -version
mvn -version
node --version
npm --version
```

`mvn -version` 输出中的 Java 版本也必须是 21。Windows 如安装了多个 JDK，可在当前 PowerShell 中设置：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot'
```

## 首次安装与启动

克隆仓库后先安装前端依赖：

```powershell
cd frontend
npm install
```

分别打开两个终端。在第一个终端启动后端：

```powershell
cd backend
mvn spring-boot:run
```

首次启动时 Flyway 会自动创建 SQLite 表。开发数据库默认位于 `backend/data/sportshop.db`。

在第二个终端启动前端：

```powershell
cd frontend
npm run dev
```

浏览器访问 `http://127.0.0.1:5173`。系统启动后直接进入页面，无需登录。

如需将运行数据放到其他目录，在启动后端前设置 `SPORTSHOP_DATA_DIR`。相对路径以 `backend` 目录为基准：

```powershell
$env:SPORTSHOP_DATA_DIR = './data-local'
mvn spring-boot:run
```

## 测试

后端单元与集成测试：

```powershell
cd backend
mvn test
```

前端单元测试和类型检查：

```powershell
cd frontend
npm test -- --run
npm run type-check
```

端到端测试首次运行前安装 Playwright Chromium：

```powershell
cd frontend
npx playwright install chromium
$env:SPORTSHOP_JAVA_HOME = $env:JAVA_HOME
npm run test:e2e
```

端到端测试会自动同时启动 Java 后端和 Vite 前端，并且只使用、重建 `backend/target/e2e-data` 下的测试数据库，不会修改 `backend/data` 中的开发数据。如果浏览器下载暂不可用，也可临时指定本机 Chromium 内核浏览器的完整可执行文件路径：

```powershell
$env:SPORTSHOP_BROWSER_EXECUTABLE = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
npm run test:e2e
```

## 清空开发数据库

以下操作不可恢复。先停止后端，并确认不再需要开发数据或已完成备份；随后在仓库根目录仅删除这三个明确文件：

```powershell
Remove-Item -LiteralPath '.\backend\data\sportshop.db' -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath '.\backend\data\sportshop.db-wal' -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath '.\backend\data\sportshop.db-shm' -Force -ErrorAction SilentlyContinue
```

下次启动后端时会重新创建空数据库。不要删除整个 `backend/data` 目录，也不要在后端运行期间复制或删除 SQLite 主文件、WAL 文件和 SHM 文件。

## 使用与安全注意

- 当前版本没有身份验证和权限控制，只适合受信任的门店内网或本机环境；不要直接暴露到公网。
- Vite 开发服务器和 Spring Boot 开发进程用于本地开发，不是生产部署方案。
- 库存数量不能在商品资料页直接修改，所有变化必须来自入库、销售、退货或库存调整单。
- 停用大类、小类、商品或 SKU 后，系统会阻止新的入库和销售交易。
- 备份 SQLite 数据前先停止后端，确保主数据库和 WAL/SHM 状态一致。
- `backend/data`、`backend/target`、`frontend/node_modules`、Playwright 报告和测试结果均已排除在 Git 之外。
