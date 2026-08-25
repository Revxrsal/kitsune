import {useState} from "react";
import reactLogo from "./assets/react.svg";
import "./App.css";
import {reverse} from "./bindings.ts";

function App() {
  const [name, setName] = useState("");
  const [button, setButton] = useState("Greet")

  async function greet() {
    const result = await reverse({
      input: name,
    })
    setButton(result)
  }

  return (
    <main className="container">
      <h1>Welcome to Tauri + React</h1>

      <div className="row">
        <a href="https://vite.dev" target="_blank">
          <img src="/vite.svg" className="logo vite" alt="Vite logo"/>
        </a>
        <a href="https://tauri.app" target="_blank">
          <img src="/tauri.svg" className="logo tauri" alt="Tauri logo"/>
        </a>
        <a href="https://react.dev" target="_blank">
          <img src={reactLogo} className="logo react" alt="React logo"/>
        </a>
      </div>
      <p>Click on the Tauri, Vite, and React logos to learn more.</p>

      <form
        className="row"
        onSubmit={(e) => {
          e.preventDefault();
          greet();
        }}
      >
        <input
          id="greet-input"
          onChange={(e) => setName(e.currentTarget.value)}
          placeholder="Enter a name..."
        />
        <button type="submit">{button}</button>
      </form>
    </main>
  );
}

export default App;
