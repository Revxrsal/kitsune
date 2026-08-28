import {useEffect, useState} from "react";
import reactLogo from "./assets/react.svg";
import "./App.css";
import {reverse, warmUp} from "./bindings.ts";

void warmUp()

function App() {
  const [name, setName] = useState("");
  const [reversed, setReversed] = useState("");

  useEffect(() => {
    reverse({value: name}).then(setReversed)
  }, [name]);

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
        onSubmit={(e) => {
          e.preventDefault();
        }}
      >
        <input
          id="greet-input"
          onChange={(e) => setName(e.currentTarget.value)}
          placeholder="Enter a name..."
        />
        <p>{reversed}</p>
      </form>
    </main>
  );
}

export default App;
